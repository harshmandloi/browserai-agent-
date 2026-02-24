package browserAI.demo.service;

import browserAI.demo.exception.DuplicateRequestException;
import browserAI.demo.exception.MissingDataException;
import browserAI.demo.fsm.ConversationState;
import browserAI.demo.model.dto.AgentRequest;
import browserAI.demo.model.dto.AgentResponse;
import browserAI.demo.model.dto.GeminiIntent;
import browserAI.demo.model.entity.ConversationSession;
import browserAI.demo.model.entity.Document;
import browserAI.demo.portal.PortalAdapter;
import browserAI.demo.portal.PortalExecutorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.time.Duration;
import java.util.Optional;

/**
 * Agent Orchestrator — the brain of the system.
 *
 * PRD-complete flow with ALL layers:
 *  1. Rate limit check
 *  2. Create/resume conversation session (FSM)
 *  3. Call Gemini to extract intent (with governance check)
 *  4. FSM: Determine if all info is available, or ask follow-up questions
 *  5. Acquire Redis lock (prevent double execution)
 *  6. Check memory/RAG for past workflows
 *  7. Check if document already exists (pre-execution dedup: reference match)
 *  8. Decrypt credentials (in-memory only, never sent to LLM)
 *  9. Execute Playwright automation (with retry via Resilience4j)
 * 10. Process document: SHA-256 hash, post-download dedup, store
 * 11. Update memory: save session summary + workflow
 * 12. Audit log every step
 * 13. Release lock, return response
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final GeminiService geminiService;
    private final CredentialVaultService credentialVaultService;
    private final PortalExecutorFactory portalExecutorFactory;
    private final DocumentProcessingService documentProcessingService;
    private final RedisLockService redisLockService;
    private final ConversationService conversationService;
    private final AuditService auditService;
    private final RateLimiterService rateLimiterService;
    private final GovernanceService governanceService;
    private final MemoryService memoryService;
    private final WebSearchService webSearchService;
    private final TokenTrackingService tokenTrackingService;
    private final DomExplorationService domExplorationService;
    private final FileStorageService fileStorageService;
    private final RequestTrackingService requestTrackingService;
    private final DocumentIntelligenceService documentIntelligenceService;
    private final SchedulerService schedulerService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String INTENT_CACHE_PREFIX = "intent:cache:";
    private static final Duration INTENT_CACHE_TTL = Duration.ofHours(6);

    public AgentOrchestrator(GeminiService geminiService,
                             CredentialVaultService credentialVaultService,
                             PortalExecutorFactory portalExecutorFactory,
                             DocumentProcessingService documentProcessingService,
                             RedisLockService redisLockService,
                             ConversationService conversationService,
                             AuditService auditService,
                             RateLimiterService rateLimiterService,
                             GovernanceService governanceService,
                             MemoryService memoryService,
                             WebSearchService webSearchService,
                             TokenTrackingService tokenTrackingService,
                             DomExplorationService domExplorationService,
                             FileStorageService fileStorageService,
                             RequestTrackingService requestTrackingService,
                             DocumentIntelligenceService documentIntelligenceService,
                             SchedulerService schedulerService,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper) {
        this.geminiService = geminiService;
        this.credentialVaultService = credentialVaultService;
        this.portalExecutorFactory = portalExecutorFactory;
        this.documentProcessingService = documentProcessingService;
        this.redisLockService = redisLockService;
        this.conversationService = conversationService;
        this.auditService = auditService;
        this.rateLimiterService = rateLimiterService;
        this.governanceService = governanceService;
        this.memoryService = memoryService;
        this.webSearchService = webSearchService;
        this.tokenTrackingService = tokenTrackingService;
        this.domExplorationService = domExplorationService;
        this.fileStorageService = fileStorageService;
        this.requestTrackingService = requestTrackingService;
        this.documentIntelligenceService = documentIntelligenceService;
        this.schedulerService = schedulerService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        // Break circular dependency — scheduler needs orchestrator to execute scheduled tasks
        this.schedulerService.setOrchestrator(this);
    }

    /**
     * Main execution pipeline — handles both new requests and continued conversations.
     */
    public AgentResponse execute(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String userId = request.getUserId();
        ConversationSession session = null;
        String requestId = null;

        try {
            // ======= STEP 1: Rate Limit Check =======
            if (!rateLimiterService.isAllowed(userId)) {
                return AgentResponse.error("Rate limit exceeded. Please wait before making more requests.");
            }
            rateLimiterService.recordRequest(userId);

            // ======= STEP 2: Create Conversation Session =======
            session = conversationService.createSession(userId);
            String sessionId = session.getSessionId();
            log.info("=== Agent START === sessionId={}, userId={}", sessionId, userId);

            auditService.logStep(sessionId, userId, "SESSION_CREATED", "step-1",
                    null, "New session for input: " + maskInput(request.getInput()), "OK", null);

            // ======= STEP 3: Extract Intent (cache-first, then Gemini) =======
            geminiService.setSessionContext(session.getSessionId(), userId);
            GeminiIntent intent;
            long llmDuration;

            GeminiIntent cachedIntent = getCachedIntent(request.getInput());
            if (cachedIntent != null) {
                intent = cachedIntent;
                llmDuration = 0;
                log.info("[Step 3] INTENT CACHE HIT — skipped Gemini call, saved tokens! {}", intent);
                auditService.logStep(sessionId, userId, "INTENT_CACHE_HIT", "step-3",
                        intent.getPortal(), "Reused cached intent: " + intent, "OK", 0L);
            } else {
                governanceService.checkLlmCallAllowed(session);
                log.info("[Step 3] Extracting intent via Gemini");

                long llmStart = System.currentTimeMillis();
                intent = geminiService.extractIntent(request.getInput());
                llmDuration = System.currentTimeMillis() - llmStart;

                session = conversationService.recordLlmCall(session, 500);
                auditService.logStep(sessionId, userId, "INTENT_EXTRACTED", "step-3",
                        intent.getPortal(), intent.toString(), "OK", llmDuration);

                cacheIntent(request.getInput(), intent);
            }

            String portal = intent.getPortal();
            String documentType = intent.getDocumentType();
            String reference = intent.getReference();

            // ======= Create Request Tracking Entry =======
            var requestLog = requestTrackingService.createRequestLog(
                    userId, request.getInput(), intent.effectiveIntentType(),
                    portal, documentType, reference);
            requestId = requestLog.getRequestId();
            log.info("[Tracking] Request ID: {}", requestId);

            // Update session with intent data
            session = conversationService.updateIntent(session, portal, documentType, reference);

            // ======= DISPATCH by Intent Type =======
            String intentType = intent.effectiveIntentType();

            if ("query".equals(intentType)) {
                return handleQueryIntent(intent, userId, requestId, sessionId, startTime);
            }

            if ("schedule".equals(intentType)) {
                return handleScheduleIntent(intent, userId, requestId, request.getInput(), sessionId, startTime);
            }

            if (intent.isBatch()) {
                return handleBatchIntent(intent, userId, requestId, sessionId, startTime, request);
            }

            // ======= STEP 4: FSM — Validate info completeness =======
            log.info("[Step 4] FSM validation");

            // 4a: Is portal valid?
            if (portal == null || portal.isBlank()) {
                session = conversationService.transition(session, ConversationState.NEED_PORTAL);
                session = conversationService.setPendingQuestion(session,
                        "Which portal/website should I download from? Please specify the website name.");
                auditService.logStep(sessionId, userId, "NEED_PORTAL", "step-4",
                        null, "Portal missing from intent", "WAITING", null);
                return AgentResponse.error("Portal name could not be determined. " + session.getPendingQuestion());
            }

            // 4b: ALL portals → DynamicPortalAdapter (LLM-guided, zero hardcoding)
            log.info("[Step 4] Dynamic mode — LLM will navigate '{}' autonomously", portal);

            if (webSearchService.isAvailable()) {
                log.info("[Step 4] Pre-searching portal URL via SerpAPI...");
                String portalUrl = webSearchService.searchPortalUrl(portal, documentType);
                if (portalUrl != null) {
                    log.info("[Step 4] SerpAPI found URL: {}", portalUrl);
                    memoryService.saveWorkflow(portal, documentType, portalUrl,
                            "SerpAPI discovered URL", null, false);
                    auditService.logStep(sessionId, userId, "SERPAPI_SEARCH", "step-4",
                            portal, "SerpAPI found URL: " + portalUrl, "OK", null);
                }
            }

            auditService.logStep(sessionId, userId, "ADAPTER_DYNAMIC", "step-4",
                    portal, "Using DynamicPortalAdapter for: " + portal, "OK", null);

            // 4c: Don't pre-check credentials — agent will explore the page first.
            // If portal requires login/data, DynamicPortalAdapter will discover this
            // during exploration and throw MissingDataException with specific field requirements.
            boolean hasCredentials = credentialVaultService.hasCredential(userId, portal);
            log.info("[Step 4] Credentials stored for '{}': {}. Agent will explore first, ask user only if needed.", portal, hasCredentials);
            auditService.logStep(sessionId, userId, "EXPLORE_FIRST", "step-4",
                    portal, "Agent will explore portal first. Credentials available: " + hasCredentials, "OK", null);

            // All info gathered — transition to EXECUTION_READY
            session = conversationService.transition(session, ConversationState.EXECUTION_READY);
            auditService.logStep(sessionId, userId, "EXECUTION_READY", "step-4",
                    portal, "All info gathered", "OK", null);

            // ======= STEP 5: Acquire Redis Lock =======
            log.info("[Step 5] Acquiring lock");
            if (!redisLockService.acquireLock(userId, reference)) {
                throw new DuplicateRequestException(
                        "Request already in progress for userId=%s, reference=%s".formatted(userId, reference));
            }

            try {
                // ======= STEP 6: Check Memory/RAG for cached steps =======
                log.info("[Step 6] Checking memory for cached workflows (skip LLM if found)");
                var cachedWorkflow = memoryService.getWorkflow(portal, documentType);
                String memoryContext = memoryService.buildWorkflowContext(portal, documentType);
                String sessionContext = memoryService.buildMemoryContext(portal, documentType);

                if (cachedWorkflow.isPresent() && cachedWorkflow.get().isVerified()) {
                    log.info("[Step 6] REUSING cached workflow — portal={}, steps={}, saves tokens!",
                            portal, cachedWorkflow.get().getWorkflowSteps());
                    auditService.logStep(sessionId, userId, "WORKFLOW_CACHE_HIT", "step-6",
                            portal, "Reusing verified workflow (saved LLM tokens). Steps: " +
                            cachedWorkflow.get().getWorkflowSteps(), "OK", null);
                } else {
                    String contextSummary = "Workflow context length: %d, Session context length: %d"
                            .formatted(memoryContext.length(), sessionContext.length());
                    auditService.logStep(sessionId, userId, "MEMORY_CHECKED", "step-6",
                            portal, contextSummary, "OK", null);
                }

                // ======= STEP 7: Pre-execution dedup check (considers documentType) =======
                log.info("[Step 7] Pre-execution dedup check (reference + type match)");
                Optional<Document> existing = documentProcessingService.findExistingByReferenceAndType(
                        userId, portal, documentType, reference);
                if (existing.isPresent()) {
                    log.info("[Step 7] Document already exists (reference match), returning cached");
                    session = conversationService.markSuccess(session);
                    long dedupDuration = System.currentTimeMillis() - startTime;
                    auditService.logStep(sessionId, userId, "DEDUP_HIT", "step-7",
                            portal, "Document found by reference match", "OK", dedupDuration);

                    saveSessionSummary(session, true, null, startTime);

                    // Auto-extract data if not already done
                    java.util.Map<String, Object> extractedData = null;
                    try {
                        var extracted = documentIntelligenceService.extractAndStore(existing.get());
                        if (extracted != null) {
                            extractedData = objectMapper.readValue(extracted.getExtractedJson(), java.util.Map.class);
                        }
                    } catch (Exception e) {
                        log.debug("Extraction on dedup hit failed (non-critical): {}", e.getMessage());
                    }

                    tokenTrackingService.persistSessionUsage(sessionId, userId, portal, documentType,
                            session.getLlmCallsCount());

                    if (requestId != null) {
                        requestTrackingService.completeRequest(requestId, "SUCCESS",
                                "Document already exists (reference match)",
                                existing.get().getId(), dedupDuration, null, sessionId);
                    }

                    AgentResponse.DocumentInfo info = documentProcessingService.toDocumentInfo(existing.get(), true);
                    AgentResponse resp = AgentResponse.success("Document already exists (reference match)", info);
                    resp.setRequestId(requestId);
                    resp.setSessionId(sessionId);
                    resp.setTokenUsage(tokenTrackingService.getSessionUsage(sessionId));
                    if (extractedData != null) resp.setExtractedData(extractedData);
                    geminiService.clearSessionContext();
                    return resp;
                }

                // ======= STEP 8: Get user data — credentials OR from intent =======
                session = conversationService.transition(session, ConversationState.EXECUTION_RUNNING);
                String fieldUsername = null;
                String fieldPassword = null;

                // Priority 1: Stored credentials
                if (credentialVaultService.hasCredential(userId, portal)) {
                    log.info("[Step 8] Decrypting stored data for portal");
                    CredentialVaultService.DecryptedCredential cred = credentialVaultService.getCredential(userId, portal);
                    fieldUsername = cred.username();
                    fieldPassword = cred.password();
                    auditService.logStep(sessionId, userId, "DATA_LOADED", "step-8",
                            portal, "User data loaded from credential vault", "OK", null);
                }

                // Priority 2: Data from intent (user provided email/name in the request text)
                if (intent.getEmail() != null && !intent.getEmail().isBlank()) {
                    if (fieldPassword == null || fieldPassword.isBlank()) {
                        fieldPassword = intent.getEmail();
                        log.info("[Step 8] Email extracted from intent: {}", intent.getEmail().substring(0, 3) + "***");
                    }
                }
                if (intent.getLastName() != null && !intent.getLastName().isBlank()) {
                    if (fieldUsername == null || fieldUsername.isBlank()) {
                        fieldUsername = intent.getLastName();
                        log.info("[Step 8] LastName extracted from intent: {}", intent.getLastName());
                    }
                }

                if (fieldUsername != null || fieldPassword != null) {
                    auditService.logStep(sessionId, userId, "DATA_READY", "step-8",
                            portal, "username=%s, email=%s (from %s)".formatted(
                                    fieldUsername != null ? "yes" : "no",
                                    fieldPassword != null ? "yes" : "no",
                                    credentialVaultService.hasCredential(userId, portal) ? "vault+intent" : "intent only"),
                            "OK", null);
                } else {
                    log.info("[Step 8] No data available — agent will explore portal and ask if needed");
                    auditService.logStep(sessionId, userId, "NO_DATA", "step-8",
                            portal, "No credentials or intent data available", "WARNING", null);
                }

                // ======= STEP 9: Execute Playwright Automation (Dynamic) =======
                log.info("[Step 9] Executing DYNAMIC portal automation: {}", portal);
                domExplorationService.setSessionContext(sessionId, userId);
                long execStart = System.currentTimeMillis();

                PortalAdapter adapter = portalExecutorFactory.getAdapter(portal);
                File downloadedFile = adapter.downloadDocument(fieldUsername, fieldPassword, documentType, reference);

                long execDuration = System.currentTimeMillis() - execStart;
                log.info("[Step 9] File downloaded: {} ({}ms)", downloadedFile.getName(), execDuration);
                auditService.logStep(sessionId, userId, "DOWNLOAD_COMPLETE", "step-9",
                        portal, "File: " + downloadedFile.getName(), "OK", execDuration);

                // ======= STEP 10: Document Processing (hash + dedup + store) =======
                log.info("[Step 10] Processing document: hash, dedup, store");
                long procStart = System.currentTimeMillis();

                DocumentProcessingService.ProcessingResult result =
                        documentProcessingService.processDocument(downloadedFile, userId, portal, documentType, reference);

                long procDuration = System.currentTimeMillis() - procStart;
                auditService.logStep(sessionId, userId, "DOCUMENT_PROCESSED", "step-10",
                        portal, "Hash: %s, Duplicate: %s".formatted(result.document().getHash(), result.duplicate()),
                        "OK", procDuration);

                // ======= STEP 11: Update Memory + Save Steps for Reuse =======
                log.info("[Step 11] Updating memory and caching workflow steps");
                session = conversationService.markSuccess(session);
                memoryService.recordWorkflowSuccess(portal, documentType);

                // Steps are saved by DynamicPortalAdapter.saveLearnedWorkflow() already.
                // Here we just verify and log. Don't overwrite the adapter's data.
                var updatedWorkflow = memoryService.getWorkflow(portal, documentType);
                if (updatedWorkflow.isPresent() && updatedWorkflow.get().getCssSelectors() != null) {
                    log.info("[Step 11] Workflow steps already cached by adapter for {}/{}. CSS selectors present.",
                            portal, documentType);
                } else {
                    // Adapter didn't save or css_selectors missing — save from StepRecorder
                    var adapterSteps = adapter.getLastNavigationSteps();
                    String executionSteps = adapterSteps.isEmpty()
                            ? "explore -> fill_form -> submit -> download"
                            : PortalAdapter.StepRecorder.toCompactString();
                    String cssSelectors = adapterSteps.stream()
                            .filter(s -> s.selector() != null)
                            .map(s -> s.action() + ":" + s.selector())
                            .reduce((a, b) -> a + " | " + b)
                            .orElse(null);
                    String url = updatedWorkflow.map(w -> w.getLoginUrl()).orElse(null);
                    memoryService.saveWorkflow(portal, documentType, url, executionSteps, cssSelectors, true);
                    log.info("[Step 11] Workflow steps saved from orchestrator: {} steps for {}/{}",
                            adapterSteps.size(), portal, documentType);
                }

                saveSessionSummary(session, true, null, startTime);
                auditService.logStep(sessionId, userId, "MEMORY_UPDATED", "step-11",
                        portal, "Session summary + workflow steps cached for reuse", "OK", null);

                // ======= STEP 12: Auto-save to local Downloads + Build Response =======
                AgentResponse.DocumentInfo info = documentProcessingService.toDocumentInfo(result.document(), result.duplicate());

                // Auto-copy to user's local ~/Downloads folder
                String localPath = fileStorageService.copyToLocalDownloads(
                        result.document().getFilePath(), portal, documentType, reference);
                if (localPath != null) {
                    info.setLocalDownloadPath(localPath);
                    log.info("[Step 12] Invoice auto-saved to local Downloads: {}", localPath);
                    auditService.logStep(sessionId, userId, "LOCAL_DOWNLOAD", "step-12",
                            portal, "Saved to: " + localPath, "OK", null);
                }

                // ======= STEP 12b: Auto-extract PDF data (Document Intelligence) =======
                java.util.Map<String, Object> extractedData = null;
                try {
                    var extracted = documentIntelligenceService.extractAndStore(result.document());
                    if (extracted != null) {
                        extractedData = objectMapper.readValue(extracted.getExtractedJson(), java.util.Map.class);
                        log.info("[Step 12b] Document intelligence: extracted {} fields", extractedData.size());
                        auditService.logStep(sessionId, userId, "DATA_EXTRACTED", "step-12b",
                                portal, "Extracted: amount=%s, vendor=%s".formatted(
                                        extracted.getTotalAmount(), extracted.getVendorName()), "OK", null);
                    }
                } catch (Exception e) {
                    log.warn("[Step 12b] Document intelligence failed (non-critical): {}", e.getMessage());
                }

                String message = result.duplicate()
                        ? "Document downloaded but was a duplicate (SHA-256 hash match). Returning existing record."
                        : "Document downloaded, processed, and stored successfully." +
                          (localPath != null ? " Also saved to: " + localPath : "");

                long totalDuration = System.currentTimeMillis() - startTime;
                auditService.logStep(sessionId, userId, "SESSION_COMPLETE", "step-12",
                        portal, message, "SUCCESS", totalDuration);

                tokenTrackingService.persistSessionUsage(sessionId, userId, portal, documentType,
                        session.getLlmCallsCount());
                var tokenUsage = tokenTrackingService.getSessionUsage(sessionId);

                log.info("=== Agent DONE === requestId={}, sessionId={} ({}ms) tokens={}",
                        requestId, sessionId, totalDuration, tokenUsage.get("totalTokens"));
                geminiService.clearSessionContext();
                domExplorationService.clearSessionContext();

                // Complete request tracking
                final String finalRequestId = requestId;
                if (finalRequestId != null) {
                    requestTrackingService.completeRequest(finalRequestId, "SUCCESS", message,
                            result.document().getId(), totalDuration,
                            tokenUsage.get("totalTokens") != null ? ((Number) tokenUsage.get("totalTokens")).longValue() : 0L,
                            sessionId);
                }

                AgentResponse resp = AgentResponse.success(message, info);
                resp.setTokenUsage(tokenUsage);
                resp.setSessionId(sessionId);
                resp.setRequestId(requestId);
                if (extractedData != null) {
                    resp.setExtractedData(extractedData);
                }
                return resp;

            } finally {
                // ======= STEP 13: Always Release Lock =======
                redisLockService.releaseLock(userId, reference);
            }

        } catch (MissingDataException e) {
            log.warn("Agent needs user input: {} — fields: {}", e.getMessage(), e.getMissingFields());
            String sessionId = session != null ? session.getSessionId() : null;
            if (session != null) {
                conversationService.transition(session, ConversationState.NEED_CREDENTIAL);
                conversationService.setPendingQuestion(session, e.getMessage());
                auditService.logStep(session.getSessionId(), userId, "NEEDS_USER_INPUT",
                        "step-9", e.getPortal(), "Agent explored portal but needs: " + e.getMissingFields(),
                        "WAITING", System.currentTimeMillis() - startTime);
                tokenTrackingService.persistSessionUsage(sessionId, userId,
                        e.getPortal(), session.getDocumentType(), session.getLlmCallsCount());
            }
            if (requestId != null) {
                requestTrackingService.completeRequest(requestId, "NEEDS_INPUT", e.getMessage(),
                        null, System.currentTimeMillis() - startTime, null, sessionId);
            }
            AgentResponse resp = AgentResponse.needsInput(sessionId, e.getMessage(), e.getMissingFields());
            resp.setTokenUsage(tokenTrackingService.getSessionUsage(sessionId != null ? sessionId : "unknown"));
            resp.setRequestId(requestId);
            geminiService.clearSessionContext();
            domExplorationService.clearSessionContext();
            return resp;

        } catch (GovernanceService.GovernanceViolationException e) {
            log.error("Governance violation", e);
            if (session != null) {
                conversationService.markFailed(session, truncateError(e.getMessage()));
                auditService.logStep(session.getSessionId(), userId, "GOVERNANCE_VIOLATION",
                        null, null, truncateError(e.getMessage()), "FAILED", System.currentTimeMillis() - startTime);
                saveSessionSummary(session, false, truncateError(e.getMessage()), startTime);
            }
            return AgentResponse.error("Governance limit exceeded: " + truncateError(e.getMessage()));

        } catch (browserAI.demo.exception.GeminiException e) {
            log.error("Gemini AI error: {}", e.getMessage());
            if (session != null) {
                conversationService.markFailed(session, truncateError(e.getMessage()));
                auditService.logStep(session.getSessionId(), userId, "GEMINI_ERROR",
                        null, session.getPortal(), truncateError(e.getMessage()), "FAILED",
                        System.currentTimeMillis() - startTime);
                saveSessionSummary(session, false, truncateError(e.getMessage()), startTime);
            }
            geminiService.clearSessionContext();
            domExplorationService.clearSessionContext();
            throw e;

        } catch (RuntimeException e) {
            log.error("Agent execution failed", e);
            if (session != null) {
                conversationService.markFailed(session, truncateError(e.getMessage()));
                auditService.logStep(session.getSessionId(), userId, "EXECUTION_FAILED",
                        null, session.getPortal(), truncateError(e.getMessage()), "FAILED",
                        System.currentTimeMillis() - startTime);
                memoryService.recordWorkflowFailure(
                        session.getPortal() != null ? session.getPortal() : "unknown",
                        session.getDocumentType() != null ? session.getDocumentType() : "unknown");
                saveSessionSummary(session, false, truncateError(e.getMessage()), startTime);
            }
            throw e;

        } catch (Exception e) {
            log.error("Agent execution failed (checked)", e);
            if (session != null) {
                conversationService.markFailed(session, truncateError(e.getMessage()));
                saveSessionSummary(session, false, truncateError(e.getMessage()), startTime);
            }
            throw new RuntimeException("Agent execution failed: " + e.getMessage(), e);
        }
    }

    // ======= INTENT HANDLERS =======

    /**
     * Handles "query" intent — answers questions about previously downloaded documents.
     */
    private AgentResponse handleQueryIntent(GeminiIntent intent, String userId, String requestId,
                                             String sessionId, long startTime) {
        log.info("[Query] Handling document query: portal={}, query='{}'",
                intent.getPortal(), intent.getQuery());

        java.util.Map<String, Object> result = documentIntelligenceService.queryDocuments(
                userId, intent.getPortal(), intent.getDocumentType(),
                intent.getQuery() != null ? intent.getQuery() : "Summarize document data");

        long duration = System.currentTimeMillis() - startTime;
        requestTrackingService.completeRequest(requestId, "SUCCESS",
                "Query answered", null, duration, null, sessionId);

        var tokenUsage = tokenTrackingService.getSessionUsage(sessionId);

        AgentResponse resp = AgentResponse.queryResult(
                "Query answered successfully", result);
        resp.setRequestId(requestId);
        resp.setSessionId(sessionId);
        resp.setTokenUsage(tokenUsage);
        geminiService.clearSessionContext();
        return resp;
    }

    /**
     * Handles "schedule" intent — creates a recurring download task.
     */
    private AgentResponse handleScheduleIntent(GeminiIntent intent, String userId, String requestId,
                                                String inputText, String sessionId, long startTime) {
        log.info("[Schedule] Creating scheduled task: portal={}, schedule='{}'",
                intent.getPortal(), intent.getSchedule());

        // Build the download command that will be re-executed on schedule
        String downloadInput = "Download %s from %s".formatted(
                intent.getDocumentType(), intent.getPortal());
        if (intent.getReference() != null) downloadInput += " for " + intent.getReference();
        if (intent.getEmail() != null) downloadInput += " and email is " + intent.getEmail();

        var scheduledTask = schedulerService.createSchedule(
                userId, requestId, downloadInput,
                intent.getPortal(), intent.getDocumentType(), intent.getReference(),
                intent.getSchedule());

        long duration = System.currentTimeMillis() - startTime;
        requestTrackingService.completeRequest(requestId, "SUCCESS",
                "Schedule created: " + scheduledTask.getScheduleDescription(),
                null, duration, null, sessionId);

        AgentResponse.ScheduleInfo scheduleInfo = new AgentResponse.ScheduleInfo();
        scheduleInfo.setScheduleId(scheduledTask.getId());
        scheduleInfo.setCronExpression(scheduledTask.getCronExpression());
        scheduleInfo.setDescription(scheduledTask.getScheduleDescription());
        scheduleInfo.setStatus("ACTIVE");

        AgentResponse resp = AgentResponse.scheduleCreated(
                "Scheduled task created: " + scheduledTask.getScheduleDescription(), scheduleInfo);
        resp.setRequestId(requestId);
        resp.setSessionId(sessionId);
        geminiService.clearSessionContext();
        return resp;
    }

    /**
     * Handles "batch" intent — downloads multiple documents sequentially.
     */
    private AgentResponse handleBatchIntent(GeminiIntent intent, String userId, String requestId,
                                             String sessionId, long startTime, AgentRequest originalRequest) {
        java.util.List<String> refs = intent.getReferences();
        if (refs == null || refs.isEmpty()) {
            refs = intent.getReference() != null ? java.util.List.of(intent.getReference()) : java.util.List.of();
        }

        log.info("[Batch] Processing {} references for portal={}", refs.size(), intent.getPortal());

        java.util.List<AgentResponse.BatchResult> results = new java.util.ArrayList<>();
        int successCount = 0;

        for (String ref : refs) {
            AgentResponse.BatchResult batchItem = new AgentResponse.BatchResult();
            batchItem.setReference(ref);

            try {
                // Create a sub-request for each reference
                String subInput = "Download %s from %s for %s".formatted(
                        intent.getDocumentType(), intent.getPortal(), ref);
                if (intent.getEmail() != null) subInput += " and email is " + intent.getEmail();
                if (intent.getLastName() != null) subInput += " and last name is " + intent.getLastName();

                AgentRequest subRequest = new AgentRequest(userId, subInput);
                AgentResponse subResponse = execute(subRequest);

                batchItem.setSuccess(subResponse.isSuccess());
                batchItem.setMessage(subResponse.getMessage());
                batchItem.setDocument(subResponse.getDocument());
                if (subResponse.isSuccess()) successCount++;

            } catch (Exception e) {
                batchItem.setSuccess(false);
                batchItem.setError(e.getMessage());
            }
            results.add(batchItem);
        }

        long duration = System.currentTimeMillis() - startTime;
        String message = "Batch complete: %d/%d successful".formatted(successCount, refs.size());
        requestTrackingService.completeRequest(requestId, successCount == refs.size() ? "SUCCESS" : "PARTIAL",
                message, null, duration, null, sessionId);

        AgentResponse resp = AgentResponse.batchResult(message, results);
        resp.setRequestId(requestId);
        resp.setSessionId(sessionId);
        geminiService.clearSessionContext();
        return resp;
    }

    /**
     * Saves session summary to memory for future RAG retrieval.
     */
    private void saveSessionSummary(ConversationSession session, boolean success,
                                    String failureReason, long startTime) {
        try {
            memoryService.saveSessionSummary(
                    session.getSessionId(),
                    session.getUserId(),
                    session.getPortal() != null ? session.getPortal() : "unknown",
                    session.getDocumentType(),
                    buildSummaryText(session, success),
                    "LLM calls: %d, Retries: %d".formatted(session.getLlmCallsCount(), session.getRetryCount()),
                    success,
                    failureReason,
                    System.currentTimeMillis() - startTime,
                    session.getLlmCallsCount()
            );
        } catch (Exception e) {
            log.warn("Failed to save session summary (non-critical): {}", e.getMessage());
        }
    }

    private String buildSummaryText(ConversationSession session, boolean success) {
        return "Session for portal '%s', document type '%s', reference '%s'. Result: %s"
                .formatted(
                        session.getPortal(),
                        session.getDocumentType(),
                        session.getReference(),
                        success ? "SUCCESS" : "FAILURE"
                );
    }

    private String maskInput(String input) {
        if (input != null && input.length() > 80) {
            return input.substring(0, 80) + "...";
        }
        return input;
    }

    private String truncateError(String message) {
        if (message != null && message.length() > 500) {
            return message.substring(0, 500) + "...[truncated]";
        }
        return message;
    }

    /**
     * Normalize user input to create a stable cache key.
     * Strips extra whitespace, lowercases, removes punctuation.
     */
    private String normalizeInput(String input) {
        if (input == null) return "";
        return input.toLowerCase().trim()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ");
    }

    private GeminiIntent getCachedIntent(String userInput) {
        try {
            String key = INTENT_CACHE_PREFIX + normalizeInput(userInput).hashCode();
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, GeminiIntent.class);
            }
        } catch (Exception e) {
            log.debug("Intent cache miss: {}", e.getMessage());
        }
        return null;
    }

    private void cacheIntent(String userInput, GeminiIntent intent) {
        try {
            String key = INTENT_CACHE_PREFIX + normalizeInput(userInput).hashCode();
            String json = objectMapper.writeValueAsString(intent);
            redisTemplate.opsForValue().set(key, json, INTENT_CACHE_TTL);
            log.debug("Intent cached for 6h: {}", key);
        } catch (Exception e) {
            log.debug("Failed to cache intent: {}", e.getMessage());
        }
    }
}
