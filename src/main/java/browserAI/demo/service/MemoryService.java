package browserAI.demo.service;

import browserAI.demo.model.entity.PortalWorkflow;
import browserAI.demo.model.entity.SessionSummary;
import browserAI.demo.repository.PortalWorkflowRepository;
import browserAI.demo.repository.SessionSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Memory / RAG Service — the agent's long-term memory.
 *
 * Stores and retrieves:
 * - Session summaries (what happened in past sessions)
 * - Portal workflows (successful navigation paths for each portal)
 *
 * The LLM uses these as context/hints so it doesn't need to learn from scratch
 * every time. For pgvector-based semantic search, embeddings would be generated
 * via Gemini embedding API and stored in the embedding column.
 *
 * Current implementation: keyword-based retrieval.
 * Production upgrade: pgvector cosine similarity search on embeddings.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int MAX_CONTEXT_SUMMARIES = 5;

    private final SessionSummaryRepository sessionSummaryRepository;
    private final PortalWorkflowRepository portalWorkflowRepository;

    public MemoryService(SessionSummaryRepository sessionSummaryRepository,
                         PortalWorkflowRepository portalWorkflowRepository) {
        this.sessionSummaryRepository = sessionSummaryRepository;
        this.portalWorkflowRepository = portalWorkflowRepository;
    }

    // ==================== Session Summaries ====================

    /**
     * Stores a summary of a completed session.
     */
    public void saveSessionSummary(String sessionId, String userId, String portal,
                                   String documentType, String summaryText, String stepsTaken,
                                   boolean wasSuccessful, String failureReason,
                                   Long totalDurationMs, int llmCallsCount) {
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(sessionId);
        summary.setUserId(userId);
        summary.setPortal(portal);
        summary.setDocumentType(documentType);
        summary.setSummaryText(summaryText);
        summary.setStepsTaken(stepsTaken);
        summary.setWasSuccessful(wasSuccessful);
        summary.setFailureReason(failureReason);
        summary.setTotalDurationMs(totalDurationMs);
        summary.setLlmCallsCount(llmCallsCount);

        sessionSummaryRepository.save(summary);
        log.info("Session summary saved for session={}, portal={}", sessionId, portal);
    }

    /**
     * Retrieves past successful session summaries for a portal.
     * These serve as "hints" for the LLM on how to navigate this portal.
     */
    public List<SessionSummary> getRelevantSummaries(String portal) {
        return sessionSummaryRepository.findRecentSuccessfulByPortal(portal, MAX_CONTEXT_SUMMARIES);
    }

    /**
     * Builds a context string from past sessions for LLM consumption.
     */
    public String buildMemoryContext(String portal, String documentType) {
        List<SessionSummary> summaries = getRelevantSummaries(portal);
        if (summaries.isEmpty()) {
            return "No prior session data available for portal: " + portal;
        }

        StringBuilder context = new StringBuilder();
        context.append("=== Past session knowledge for portal '%s' ===\n".formatted(portal));
        for (SessionSummary s : summaries) {
            context.append("- Session: %s | Type: %s | Success: %s\n".formatted(
                    s.getSessionId(), s.getDocumentType(), s.isWasSuccessful()));
            context.append("  Steps: %s\n".formatted(s.getStepsTaken()));
            if (s.getFailureReason() != null) {
                context.append("  Failure reason: %s\n".formatted(s.getFailureReason()));
            }
        }
        return context.toString();
    }

    // ==================== Portal Workflows ====================

    /**
     * Stores or updates a verified portal workflow.
     */
    public void saveWorkflow(String portal, String documentType, String loginUrl,
                             String workflowSteps, String cssSelectors, boolean verified) {
        PortalWorkflow workflow = portalWorkflowRepository
                .findByPortalAndDocumentType(portal, documentType)
                .orElse(new PortalWorkflow());

        workflow.setPortal(portal);
        workflow.setDocumentType(documentType);
        workflow.setLoginUrl(loginUrl);
        workflow.setWorkflowSteps(workflowSteps);
        workflow.setCssSelectors(cssSelectors);
        workflow.setVerified(verified);

        portalWorkflowRepository.save(workflow);
        log.info("Portal workflow saved/updated for portal={}, type={}", portal, documentType);
    }

    /**
     * Retrieves a known workflow for a portal + document type.
     */
    public Optional<PortalWorkflow> getWorkflow(String portal, String documentType) {
        return portalWorkflowRepository.findByPortalAndDocumentType(portal, documentType);
    }

    /**
     * Gets all verified workflows for a portal.
     */
    public List<PortalWorkflow> getVerifiedWorkflows(String portal) {
        return portalWorkflowRepository.findVerifiedByPortal(portal);
    }

    /**
     * Finds a sibling workflow for the same portal but different document type.
     * Used for shared path prefix reuse — e.g. IndiGo "invoice" workflow can
     * provide the common prefix (navigate → fill PNR → fill email → submit)
     * for an "gstinformation" request, avoiding redundant LLM calls.
     */
    public Optional<PortalWorkflow> getSiblingWorkflow(String portal, String excludeDocumentType) {
        return portalWorkflowRepository.findVerifiedByPortal(portal).stream()
                .filter(w -> !w.getDocumentType().equalsIgnoreCase(excludeDocumentType))
                .filter(w -> w.getCssSelectors() != null && !w.getCssSelectors().isBlank())
                .findFirst();
    }

    /**
     * Records a workflow success — increases reliability score.
     */
    public void recordWorkflowSuccess(String portal, String documentType) {
        portalWorkflowRepository.findByPortalAndDocumentType(portal, documentType)
                .ifPresent(workflow -> {
                    workflow.recordSuccess();
                    portalWorkflowRepository.save(workflow);
                });
    }

    /**
     * Records a workflow failure — may trigger re-learning.
     */
    public void recordWorkflowFailure(String portal, String documentType) {
        portalWorkflowRepository.findByPortalAndDocumentType(portal, documentType)
                .ifPresent(workflow -> {
                    workflow.recordFailure();
                    portalWorkflowRepository.save(workflow);
                });
    }

    /**
     * Builds workflow hints for LLM context.
     */
    public String buildWorkflowContext(String portal, String documentType) {
        Optional<PortalWorkflow> workflow = getWorkflow(portal, documentType);
        if (workflow.isEmpty()) {
            List<PortalWorkflow> portalWorkflows = getVerifiedWorkflows(portal);
            if (portalWorkflows.isEmpty()) {
                return "No known workflow for portal: " + portal;
            }
            // Check if a sibling workflow was used for prefix replay
            Optional<PortalWorkflow> sibling = getSiblingWorkflow(portal, documentType);
            StringBuilder ctx = new StringBuilder();
            ctx.append("No exact workflow match for '%s'. ".formatted(documentType));
            if (sibling.isPresent()) {
                ctx.append("IMPORTANT: Common prefix steps (navigate, fill form, submit) have already been ")
                   .append("replayed from the '%s' workflow. ".formatted(sibling.get().getDocumentType()))
                   .append("The page is already past the form submission. You only need to find and interact with ")
                   .append("the '%s' section/button — do NOT re-fill the form.\n".formatted(documentType));
                ctx.append("Sibling workflow steps: %s\n".formatted(sibling.get().getWorkflowSteps()));
            }
            ctx.append("Related workflows:\n");
            ctx.append(portalWorkflows.stream()
                    .map(w -> "- Type: %s | Steps: %s".formatted(w.getDocumentType(), w.getWorkflowSteps()))
                    .collect(Collectors.joining("\n")));
            return ctx.toString();
        }

        PortalWorkflow w = workflow.get();
        return """
                === Known workflow for %s / %s ===
                Login URL: %s
                Steps: %s
                CSS Selectors: %s
                Success rate: %d successes / %d failures
                Verified: %s
                """.formatted(portal, documentType, w.getLoginUrl(),
                w.getWorkflowSteps(), w.getCssSelectors(),
                w.getSuccessCount(), w.getFailureCount(), w.isVerified());
    }
}
