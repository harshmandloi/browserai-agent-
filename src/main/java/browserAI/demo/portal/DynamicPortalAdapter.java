package browserAI.demo.portal;

import browserAI.demo.captcha.CaptchaDetector;
import browserAI.demo.captcha.CaptchaResult;
import browserAI.demo.exception.DownloadTimeoutException;
import browserAI.demo.exception.GeminiException;
import browserAI.demo.exception.LoginFailureException;
import browserAI.demo.exception.MissingDataException;
import browserAI.demo.service.CaptchaSolverService;
import browserAI.demo.service.DomExplorationService;
import browserAI.demo.service.DomExplorationService.DomSummary;
import browserAI.demo.service.DomExplorationService.ExplorationAction;
import browserAI.demo.service.GovernanceService;
import browserAI.demo.service.MemoryService;
import browserAI.demo.service.OtpService;
import browserAI.demo.service.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Component
public class DynamicPortalAdapter {

    private static final Logger log = LoggerFactory.getLogger(DynamicPortalAdapter.class);
    private static final int MAX_EXPLORATION_STEPS = 10;
    private static final long MAX_EXECUTION_MS = 45_000; // 45 seconds hard limit

    private final DomExplorationService domExplorationService;
    private final OtpService otpService;
    private final CaptchaSolverService captchaSolverService;
    private final MemoryService memoryService;
    private final GovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final WebSearchService webSearchService;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${agent.playwright-headless}")
    private boolean headless;

    @Value("${agent.download-timeout-seconds}")
    private int downloadTimeoutSeconds;

    @Value("${storage.base-path}")
    private String storagePath;

    public DynamicPortalAdapter(DomExplorationService domExplorationService,
                                OtpService otpService,
                                CaptchaSolverService captchaSolverService,
                                MemoryService memoryService,
                                GovernanceService governanceService,
                                ObjectMapper objectMapper,
                                WebSearchService webSearchService) {
        this.domExplorationService = domExplorationService;
        this.otpService = otpService;
        this.captchaSolverService = captchaSolverService;
        this.memoryService = memoryService;
        this.governanceService = governanceService;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.webSearchService = webSearchService;
    }

    public File downloadDocument(String portalName, String username, String password,
                                 String documentType, String reference) {
        final long executionStart = System.currentTimeMillis();
        log.info("[Dynamic] ===== STARTING dynamic exploration (timeout={}ms) =====", MAX_EXECUTION_MS);
        log.info("[Dynamic] portal={}, type={}, ref={}", portalName, documentType, reference);
        log.info("[Dynamic] username(last_name/name)={}, password(email/pass)={}",
                maskValue(username), maskValue(password));
        PortalAdapter.StepRecorder.clear();

        String startUrl = resolvePortalUrl(portalName, documentType, reference);

        log.info("[Dynamic] Resolved start URL: {}", startUrl);

        String debugDir = Paths.get(storagePath, "debug", portalName).toString();
        new File(debugDir).mkdirs();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(headless)
                            .setArgs(Arrays.asList(
                                    "--disable-blink-features=AutomationControlled",
                                    "--no-sandbox"
                            ))
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setAcceptDownloads(true)
                            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                            .setViewportSize(1366, 768)
                            .setLocale("en-IN")
            );

            Page page = context.newPage();
            page.setDefaultTimeout(downloadTimeoutSeconds * 1000.0);
            page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
            page.addInitScript("window.print = () => console.log('print dialog suppressed by agent');");

            try {
                // ── FAST PATH for image/wallpaper requests — no LLM needed ──
                if (isImageDocType(documentType)) {
                    log.info("[Dynamic:Image] Fast path — directly downloading image from search results");
                    page.navigate(startUrl);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    waitSafe(page, 3000);
                    saveScreenshot(page, debugDir, "image_search");

                    // Try clicking the first image to open detail/full-size view
                    try {
                        Locator firstImg = page.locator("figure a, [data-testid*='photo'] a, .photo-grid a, a[itemprop='contentUrl'], a img").first();
                        if (firstImg.isVisible()) {
                            log.info("[Dynamic:Image] Clicking first image to open detail view...");
                            firstImg.click(new Locator.ClickOptions().setTimeout(5000));
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 2000);
                            saveScreenshot(page, debugDir, "image_detail");
                        }
                    } catch (Exception e) {
                        log.debug("[Dynamic:Image] Could not click first image: {}", e.getMessage());
                    }

                    // Try download button on the detail page
                    File imgFile = tryAutoDownload(page, portalName, documentType, reference);
                    if (imgFile != null) {
                        log.info("[Dynamic:Image] ===== Image downloaded via button: {} =====", imgFile.getName());
                        return imgFile;
                    }

                    // Fallback: directly grab the largest image on the page
                    imgFile = saveDirectImage(page, portalName, documentType, reference);
                    if (imgFile != null) {
                        log.info("[Dynamic:Image] ===== Image downloaded directly: {} =====", imgFile.getName());
                        return imgFile;
                    }

                    log.warn("[Dynamic:Image] Fast path failed — falling through to LLM exploration");
                }

                // ── Try cached workflow replay first (ZERO LLM tokens) ──
                var cachedWorkflow = memoryService.getWorkflow(portalName, documentType);
                if (cachedWorkflow.isPresent() && cachedWorkflow.get().isVerified()
                        && cachedWorkflow.get().getCssSelectors() != null) {
                    log.info("[Dynamic] REPLAYING cached workflow — no LLM calls needed!");
                    File replayResult = replayCachedWorkflow(page, cachedWorkflow.get(),
                            username, password, reference, portalName, documentType, startUrl, debugDir);
                    if (replayResult != null) {
                        log.info("[Dynamic] Cached workflow replay SUCCESS — saved all LLM tokens!");
                        return replayResult;
                    }
                    log.warn("[Dynamic] Cached workflow replay FAILED — falling back to LLM exploration");
                }

                // ── Resume from partial workflow (saved when previous attempt needed credentials) ──
                boolean prefixReplayed = false;
                if (cachedWorkflow.isPresent() && !cachedWorkflow.get().isVerified()
                        && cachedWorkflow.get().getCssSelectors() != null) {
                    log.info("[Dynamic] PARTIAL workflow found from previous attempt — resuming exploration");
                    prefixReplayed = replayCommonPrefix(page, cachedWorkflow.get(),
                            username, password, reference, startUrl, debugDir);
                    if (prefixReplayed) {
                        log.info("[Dynamic] Partial workflow replayed — agent resumes from where credentials were missing");
                    } else {
                        log.warn("[Dynamic] Partial workflow replay failed — starting fresh exploration");
                    }
                }

                // ── Shared prefix replay: reuse common steps from a sibling workflow ──
                if (!prefixReplayed && (cachedWorkflow.isEmpty() || cachedWorkflow.get().getCssSelectors() == null)) {
                    var siblingWorkflow = memoryService.getSiblingWorkflow(portalName, documentType);
                    if (siblingWorkflow.isPresent()) {
                        log.info("[Dynamic] Found sibling workflow (type={}) — replaying common prefix to save tokens!",
                                siblingWorkflow.get().getDocumentType());
                        prefixReplayed = replayCommonPrefix(page, siblingWorkflow.get(),
                                username, password, reference, startUrl, debugDir);
                        if (prefixReplayed) {
                            log.info("[Dynamic] Common prefix replayed. LLM will take over from here for '{}'", documentType);
                        } else {
                            log.warn("[Dynamic] Prefix replay failed — starting full exploration");
                        }
                    }
                }

                if (!prefixReplayed) {
                    page.navigate(startUrl);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    waitSafe(page, 2000);
                    saveScreenshot(page, debugDir, "00_start");
                    PortalAdapter.StepRecorder.record("navigate", startUrl, null, "portal start URL");
                }

                // Build memory context AFTER prefix decision so LLM knows the current page state
                String memoryContext = memoryService.buildWorkflowContext(portalName, documentType);
                if (prefixReplayed) {
                    memoryContext += "\n\n>>> RESUME ACTIVE: Previous navigation steps were replayed. " +
                            "The page is at the point where the previous attempt needed data (credentials/email/etc). " +
                            "Data is now available. Continue from here — do NOT navigate back or re-fill already-filled forms. <<<";
                }

                String goal = buildGoal(portalName, documentType, reference, username, password);
                List<String> actionHistory = new ArrayList<>();
                Map<String, Integer> actionCounts = new HashMap<>();
                Set<String> failedDownloadSelectors = new HashSet<>();
                boolean autoDownloadAttempted = false;
                File downloadedFile = null;

                for (int step = 0; step < MAX_EXPLORATION_STEPS; step++) {
                    long elapsed = System.currentTimeMillis() - executionStart;
                    if (elapsed > MAX_EXECUTION_MS) {
                        log.error("[Dynamic] TIMEOUT after {}ms (limit={}ms) at step {}. Aborting.",
                                elapsed, MAX_EXECUTION_MS, step + 1);
                        // Try to salvage something before timeout
                        File salvaged = tryAutoDownload(page, portalName, documentType, reference);
                        if (salvaged != null) {
                            saveLearnedWorkflow(portalName, documentType, startUrl, actionHistory);
                            return salvaged;
                        }
                        salvaged = savePageAsPdf(page, portalName, documentType, reference, debugDir);
                        if (salvaged != null) return salvaged;
                        throw new DownloadTimeoutException(
                                "Execution timeout: %ds exceeded. Portal: %s. Try again or simplify the request."
                                        .formatted(MAX_EXECUTION_MS / 1000, portalName));
                    }

                    governanceService.checkExplorationDepth(step);
                    log.info("[Dynamic] Step {} — URL: {} (elapsed: {}ms)", step + 1, page.url(), elapsed);
                    saveScreenshot(page, debugDir, "step_%02d".formatted(step + 1));

                    if (isOtpPage(page)) {
                        log.info("[Dynamic] OTP page detected at step {}", step + 1);
                        String otp = otpService.waitForOtp(username, 120);
                        if (otp == null) {
                            throw new LoginFailureException("OTP timeout — user did not provide OTP within 2 minutes");
                        }
                        fillOtp(page, otp);
                        actionHistory.add("Entered OTP");
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                        waitSafe(page, 2000);
                        continue;
                    }

                    if (CaptchaDetector.isCaptchaPresent(page)) {
                        log.info("[Dynamic] CAPTCHA detected at step {}", step + 1);
                        CaptchaResult captchaResult = captchaSolverService.detectAndSolve(page, username);
                        actionHistory.add("CAPTCHA solved: %s (type=%s)"
                                .formatted(captchaResult.isSolved(), captchaResult.getType()));
                        if (!captchaResult.isSolved()) {
                            throw new RuntimeException("CAPTCHA could not be solved: " + captchaResult.getMessage());
                        }
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                        waitSafe(page, 2000);
                        continue;
                    }

                    DomSummary domSummary = domExplorationService.extractDomSummary(page);

                    // Remove elements whose selectors already failed download attempts
                    if (!failedDownloadSelectors.isEmpty()) {
                        domSummary.getLinks().removeIf(el -> failedDownloadSelectors.contains(el.getSelector()));
                        domSummary.getButtons().removeIf(el -> failedDownloadSelectors.contains(el.getSelector()));
                    }

                    ExplorationAction action = domExplorationService.decideNextAction(
                            domSummary, goal, memoryContext, actionHistory, step);

                    log.info("[Dynamic] LLM decided: action={}, selector={}, value={}, reasoning={}",
                            action.getAction(), action.getSelector(), action.getValue(), action.getReasoning());

                    String actionKey = action.getAction() + ":" + action.getSelector();
                    actionCounts.merge(actionKey, 1, Integer::sum);
                    governanceService.checkRepeatedAction(actionKey, actionCounts.get(actionKey));

                    switch (action.getAction().toLowerCase()) {
                        case "click" -> {
                            actionHistory.add("Click: %s (%s)".formatted(action.getSelector(), action.getReasoning()));
                            page.click(action.getSelector());
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 1500);
                            PortalAdapter.StepRecorder.record("click", page.url(), action.getSelector(), action.getReasoning());
                        }
                        case "fill" -> {
                            String rawValue = action.getValue();
                            String value = resolveValue(rawValue, username, password, reference);

                            if (value.isBlank() && rawValue != null && rawValue.contains("{{")) {
                                String needed = rawValue.replace("{{", "").replace("}}", "");
                                log.warn("[Dynamic] Cannot fill field — missing data: {} (placeholder: {})", needed, rawValue);
                                saveScreenshot(page, debugDir, "missing_data_" + needed);

                                List<String> missingFields = detectMissingFields(rawValue, needed);

                                String credHint = missingFields.stream()
                                        .anyMatch(f -> f.contains("password") || f.contains("PIN") || f.contains("email") || f.contains("phone"))
                                        ? "Please provide via POST /api/credentials and re-submit."
                                        : "Please include this information in your request and re-submit.";

                                throw new MissingDataException(portalName,
                                        "Portal '%s' requires: %s. %s".formatted(
                                                portalName, String.join(", ", missingFields), credHint),
                                        missingFields);
                            }

                            actionHistory.add("Fill: %s with [masked] (%s)".formatted(action.getSelector(), action.getReasoning()));
                            page.fill(action.getSelector(), value);
                            waitSafe(page, 500);
                            // Save with rawValue so replay knows EXACTLY which value type goes here
                            String valueTag = rawValue != null ? rawValue : "{{unknown}}";
                            PortalAdapter.StepRecorder.record("fill", page.url(), action.getSelector(), "val=" + valueTag);
                        }
                        case "navigate" -> {
                            actionHistory.add("Navigate: %s (%s)".formatted(action.getUrl(), action.getReasoning()));
                            page.navigate(action.getUrl());
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 2000);
                            PortalAdapter.StepRecorder.record("navigate", action.getUrl(), null, action.getReasoning());
                        }
                        case "download" -> {
                            if (failedDownloadSelectors.contains(action.getSelector())) {
                                log.warn("[Dynamic] Selector '{}' already failed — skipping and telling LLM", action.getSelector());
                                actionHistory.add("SKIP: '%s' already tried and FAILED — use a DIFFERENT selector".formatted(action.getSelector()));
                                continue;
                            }
                            actionHistory.add("Download: %s (%s)".formatted(action.getSelector(), action.getReasoning()));
                            downloadedFile = executeDownload(page, action.getSelector(), portalName, documentType, reference);
                            if (downloadedFile != null) {
                                PortalAdapter.StepRecorder.record("download", page.url(), action.getSelector(), "file downloaded");
                            } else {
                                failedDownloadSelectors.add(action.getSelector());
                                actionHistory.add("FAILED: download from '%s' returned no file or file too small — DO NOT use this selector again, try a different one".formatted(action.getSelector()));
                            }
                        }
                        case "done" -> {
                            log.info("[Dynamic] LLM says done. Trying download buttons first, then CDP fallback...");

                            // Try clicking Download/Save buttons FIRST — to get real invoice from popup
                            if (!autoDownloadAttempted) {
                                autoDownloadAttempted = true;
                                downloadedFile = tryAutoDownload(page, portalName, documentType, reference);
                            }

                            // Fallback: save current page as PDF via CDP
                            if (downloadedFile == null) {
                                log.info("[Dynamic] No download button worked — saving current page as PDF");
                                downloadedFile = savePageAsPdf(page, portalName, documentType, reference, debugDir);
                            }

                            if (downloadedFile == null && hasVisibleDownloadElements(page)) {
                                log.info("[Dynamic] Download elements visible — continuing exploration...");
                                actionHistory.add("Override: download elements found, LLM 'done' overridden");
                                continue;
                            }
                        }
                        case "stuck" -> {
                            log.warn("[Dynamic] LLM says stuck: {}. Trying auto-download fallback...", action.getReasoning());
                            saveScreenshot(page, debugDir, "stuck_step_%02d".formatted(step + 1));

                            // Before giving up, try auto-download — page might have links the LLM missed
                            downloadedFile = tryAutoDownload(page, portalName, documentType, reference);
                            if (downloadedFile != null) {
                                log.info("[Dynamic] Auto-download succeeded on stuck page!");
                                PortalAdapter.StepRecorder.record("download", page.url(), "auto-download", "auto-detected download link");
                                break;
                            }

                            // Try saving the page itself as the document
                            downloadedFile = savePageAsPdf(page, portalName, documentType, reference, debugDir);
                            if (downloadedFile != null) {
                                log.info("[Dynamic] Saved current page as document on stuck page");
                                PortalAdapter.StepRecorder.record("download", page.url(), "page-save", "saved page as document");
                                break;
                            }

                            // Parse reasoning to figure out what data is missing — supports all portal types
                            String reason = action.getReasoning() != null ? action.getReasoning().toLowerCase() : "";
                            List<String> neededFields = detectMissingFieldsFromReasoning(reason);

                            if (!neededFields.isEmpty() || reason.contains("missing") || reason.contains("need") ||
                                    reason.contains("require") || reason.contains("without") || reason.contains("cannot proceed")) {
                                throw new MissingDataException(portalName,
                                        "Agent needs more data for '%s': %s".formatted(portalName, action.getReasoning()),
                                        neededFields);
                            }

                            throw new RuntimeException("Dynamic exploration stuck at step %d: %s"
                                    .formatted(step + 1, action.getReasoning()));
                        }
                        default -> {
                            log.warn("[Dynamic] Unknown action: {}", action.getAction());
                            actionHistory.add("Unknown action: " + action.getAction());
                        }
                    }

                    if (downloadedFile != null) {
                        log.info("[Dynamic] ===== File downloaded: {} =====", downloadedFile.getName());
                        saveLearnedWorkflow(portalName, documentType, startUrl, actionHistory);
                        return downloadedFile;
                    }
                }

                throw new DownloadTimeoutException(
                        "Dynamic exploration exhausted %d steps without finding download for portal: %s"
                                .formatted(MAX_EXPLORATION_STEPS, portalName));

            } finally {
                saveScreenshot(page, debugDir, "final_state");
                context.close();
                browser.close();
            }
        } catch (MissingDataException e) {
            // Save partial workflow so retry resumes from here instead of starting over
            savePartialWorkflow(portalName, documentType, startUrl);
            throw e;
        } catch (LoginFailureException | DownloadTimeoutException | GovernanceService.GovernanceViolationException | GeminiException e) {
            throw e;
        } catch (TimeoutError e) {
            throw new DownloadTimeoutException(
                    "Dynamic portal timeout after %d seconds for portal: %s".formatted(downloadTimeoutSeconds, portalName));
        } catch (Exception e) {
            log.error("[Dynamic] Exploration failed for portal: {}", portalName, e);
            throw new RuntimeException("Dynamic portal exploration failed for %s: %s".formatted(portalName, e.getMessage()), e);
        }
    }

    /**
     * Resolve the MOST DIRECT portal URL for downloading the document.
     * Priority: Memory → SerpAPI → LLM → Fallback.
     * Key insight: don't assume login is needed. Search for direct document pages first.
     */
    private String resolvePortalUrl(String portalName, String documentType, String reference) {
        // 0a. For check-in requests, prioritize the airline's web check-in page
        if (isCheckInDocType(documentType)) {
            var workflow = memoryService.getWorkflow(portalName, documentType);
            if (workflow.isPresent() && workflow.get().getLoginUrl() != null) {
                return workflow.get().getLoginUrl();
            }
            if (webSearchService.isAvailable()) {
                String checkInUrl = webSearchService.searchPortalUrl(portalName, "web check-in");
                if (checkInUrl != null) {
                    memoryService.saveWorkflow(portalName, documentType, checkInUrl,
                            "SerpAPI discovered check-in URL", null, false);
                    return checkInUrl;
                }
            }
            return "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(portalName + " web check-in online", java.nio.charset.StandardCharsets.UTF_8);
        }

        // 0b. For image sites, construct direct search URL (skip manual search)
        if (isImageDocType(documentType)) {
            String query = (reference != null && !reference.isBlank()) ? reference : documentType;
            String encoded = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

            return switch (portalName.toLowerCase()) {
                case "unsplash" -> "https://unsplash.com/s/photos/" + encoded;
                case "pexels" -> "https://www.pexels.com/search/" + encoded + "/";
                case "pixabay" -> "https://pixabay.com/images/search/" + encoded + "/";
                default -> "https://unsplash.com/s/photos/" + encoded;
            };
        }

        // 1. Check memory — we might already know the direct URL
        var workflow = memoryService.getWorkflow(portalName, documentType);
        if (workflow.isPresent() && workflow.get().getLoginUrl() != null) {
            log.info("[Dynamic] URL from memory: {}", workflow.get().getLoginUrl());
            return workflow.get().getLoginUrl();
        }

        // 1.5. Check sibling workflows — same portal, different doc type may share the same entry URL
        var siblingWorkflow = memoryService.getSiblingWorkflow(portalName, documentType);
        if (siblingWorkflow.isPresent() && siblingWorkflow.get().getLoginUrl() != null) {
            String siblingUrl = siblingWorkflow.get().getLoginUrl();
            log.info("[Dynamic] URL from sibling workflow (type={}): {}",
                    siblingWorkflow.get().getDocumentType(), siblingUrl);
            return siblingUrl;
        }

        // 2. SerpAPI — search for the most direct path (no login assumption)
        if (webSearchService.isAvailable()) {
            log.info("[Dynamic] Searching for DIRECT document URL via SerpAPI...");
            String serpUrl = webSearchService.searchPortalUrl(portalName, documentType);
            if (serpUrl != null && !serpUrl.isBlank()) {
                log.info("[Dynamic] SerpAPI found direct URL: {}", serpUrl);
                // Save for future reuse
                memoryService.saveWorkflow(portalName, documentType, serpUrl,
                        "SerpAPI discovered direct URL", null, false);
                return serpUrl;
            }
        }

        // 3. Ask Gemini — with emphasis on finding the DIRECT page, not login
        log.info("[Dynamic] Asking Gemini for the most direct URL...");
        String docTypeClean = documentType != null ? documentType.replace("_", " ") : "document";
        String prompt = """
                You are a web automation assistant. I need the MOST DIRECT URL to view/download
                a '%s' from '%s'.
                
                CRITICAL RULES:
                - Find the DIRECT page where the user can enter details and get the document
                - Do NOT return a login page if a direct document retrieval page exists
                - Look for pages like: "view document", "download", "manage booking", "order history",
                  "account statement", "bill payment", "certificate download" etc.
                - Support ALL portal types: airlines, banks, government (.gov.in), utilities, e-commerce, insurance
                - Prefer HTTPS URLs
                - Return ONLY the URL. No explanation, no markdown, no backticks.
                """.formatted(docTypeClean, portalName);

        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", 0.0)
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String resolvedUrl = root.path("candidates").get(0).path("content")
                        .path("parts").get(0).path("text").asText().trim();
                resolvedUrl = resolvedUrl.replaceAll("[`\\s\"']", "");
                if (resolvedUrl.startsWith("http")) {
                    log.info("[Dynamic] Gemini suggested URL: {}", resolvedUrl);
                    return resolvedUrl;
                }
            }
        } catch (Exception e) {
            log.warn("[Dynamic] LLM URL resolution failed: {}", e.getMessage());
        }

        // 4. Fallback — use Google search as last resort (no hardcoded TLD assumption)
        return "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(portalName + " " + docTypeClean + " download page", java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Smart goal builder — gives the LLM full context about what data is available
     * and what the page is asking for. Adapts the strategy based on portal type.
     */
    private String buildGoal(String portalName, String documentType, String reference,
                             String username, String password) {
        StringBuilder goal = new StringBuilder();
        boolean isImage = isImageDocType(documentType);
        boolean isCheckIn = isCheckInDocType(documentType);

        if (isCheckIn) {
            goal.append("GOAL: Perform WEB CHECK-IN on '%s' airline and DOWNLOAD the BOARDING PASS.\n\n".formatted(portalName));
            goal.append("""
                    CHECK-IN SPECIFIC INSTRUCTIONS:
                    1. Navigate to the airline's WEB CHECK-IN page (not booking or manage trip)
                    2. Enter PNR/booking reference and last name/surname
                    3. Click Submit/Continue/Check-in button
                    4. If seat selection appears → accept any available seat OR skip if possible
                    5. If add-ons/meals/baggage offers appear → skip/decline/continue
                    6. If terms & conditions appear → accept/agree and continue
                    7. If passenger details confirmation appears → confirm and continue
                    8. FINAL GOAL: Reach the BOARDING PASS page and DOWNLOAD it as PDF
                    9. Look for "Download Boarding Pass", "Download", "Save", "Print" buttons
                    
                    """);
        } else if (isImage) {
            goal.append("GOAL: Find and download a HIGH QUALITY '%s' image from '%s'.\n".formatted(
                    reference != null ? reference : documentType, portalName));
            goal.append("Search for the image, click on it to open full size, then click the Download button.\n\n");
        } else {
            goal.append("GOAL: Download a '%s' document from the '%s' portal.\n\n".formatted(documentType, portalName));
        }

        // Tell the LLM exactly what data is available
        goal.append("AVAILABLE USER DATA:\n");
        boolean hasAnyData = false;

        if (reference != null && !reference.isBlank()) {
            goal.append("  ✓ PNR / Booking Reference / Order ID / Invoice Number: {{reference}} = %s\n".formatted(reference));
            hasAnyData = true;
        } else {
            goal.append("  ✗ No PNR/reference provided\n");
        }

        if (username != null && !username.isBlank()) {
            goal.append("  ✓ Last Name / Surname / Name / Username: {{username}} = %s\n".formatted(username));
            hasAnyData = true;
        } else {
            goal.append("  ✗ No name/username provided\n");
        }

        if (password != null && !password.isBlank()) {
            boolean looksLikeEmail = password.contains("@");
            if (looksLikeEmail) {
                goal.append("  ✓ Email Address: {{email}} = %s\n".formatted(password));
            } else {
                goal.append("  ✓ Password: {{password}} = [hidden]\n");
            }
            hasAnyData = true;
        } else {
            goal.append("  ✗ No email/password provided\n");
        }

        if (!hasAnyData) {
            goal.append("\n⚠ WARNING: No user data available. Just explore the portal and identify what fields are required.\n");
            goal.append("If the portal requires data to proceed, report action='stuck' with reasoning explaining what fields are needed.\n");
        }

        goal.append("""
                
                STRATEGY:
                1. FIRST: Analyze the current page. What type of form/interface is it?
                   - Data lookup form (PNR, order ID, account number, Aadhaar, PAN, policy number, consumer number)
                   - Login form (email/username + password) → Traditional authentication
                   - Search/retrieval form → Direct document lookup
                   - OTP verification page → Wait for OTP
                   - CAPTCHA page → Solve CAPTCHA
                   - "I'm not a robot" checkbox → Click it
                   
                2. FILL FIELDS using the correct placeholder based on the FIELD LABEL:
                
                   IDENTITY / NAME fields:
                   - Last Name / Surname / Family Name → {{username}}
                   - First Name / Full Name / Name → {{name}}
                   - Father's Name / Spouse Name → {{father_name}}
                
                   AUTH fields:
                   - Email / Email Address → {{email}}
                   - Password / Secret → {{password}}
                   - PIN / MPIN / Transaction PIN → {{pin}}
                   - Mobile / Phone Number → {{mobile}}
                   - Username / User ID → {{email}} or {{username}}
                
                   REFERENCE / ID fields (all map to the user's reference value):
                   - PNR / Booking Reference → {{pnr}}
                   - Order ID / Order Number → {{order_id}}
                   - Invoice Number → {{invoice_number}}
                   - Ticket Number → {{ticket}}
                
                   GOVERNMENT ID fields:
                   - Aadhaar Number / UID → {{aadhaar}}
                   - PAN / PAN Number → {{pan}}
                   - GSTIN / GST Number → {{gstin}}
                   - Voter ID / EPIC → {{voter_id}}
                   - Driving License / DL Number → {{driving_license}}
                   - Passport Number → {{passport}}
                   - UAN (EPF) → {{uan}}
                   - DIN / CIN → {{din}} / {{cin}}
                
                   BANK / FINANCIAL fields:
                   - Account Number → {{account_number}}
                   - IFSC Code → {{ifsc}}
                   - Card Number → {{card_number}}
                   - Loan Account Number → {{loan_number}}
                   - Folio Number → {{folio}}
                
                   UTILITY fields:
                   - Consumer Number / Consumer ID → {{consumer_number}}
                   - Meter Number → {{meter_number}}
                   - Bill Number → {{bill_number}}
                   - Connection ID / CA Number → {{connection_id}}
                
                   INSURANCE fields:
                   - Policy Number → {{policy_number}}
                   - Claim Number → {{claim_number}}
                
                   PERSONAL fields:
                   - Date of Birth / DOB → {{dob}}
                
                3. SUBMIT: Click submit/search/retrieve/continue/verify/proceed button
                
                4. HANDLE VERIFICATION if needed:
                   - OTP page → Report action="otp" to wait for user input
                   - CAPTCHA image → Report action="captcha" to solve via AI
                   - reCAPTCHA / hCaptcha checkbox → Click the checkbox element
                   - "I'm not a robot" → Click it
                   
                5. NAVIGATE: After form submission, look for the document section
                   - Look for ANY tabs, links, or buttons that lead to the requested document type
                   
                6. DOWNLOAD — THIS IS THE MOST IMPORTANT STEP:
                   - Your job is NOT done until you CLICK a download button/link/icon
                   - If you see ANY download icon, "Download" button, PDF link, save button → use action="download"
                   - NEVER use action="done" while a download button/icon is visible on the page
                   - Even if the document is visible on page, you MUST still click Download
                   - Only use action="done" if there is absolutely NO download option on the page
                
                IMPORTANT RULES:
                - NOT every portal needs login! Many portals just need a reference ID
                - Match placeholder to what the FIELD LABEL asks for, not the HTML input type
                - If a required field has no data available, use action="stuck" with reasoning listing EXACTLY what data is needed
                - Support ALL portal types: airlines, banks, government (.gov.in), utilities, insurance, e-commerce, telecom
                - ALWAYS prefer downloading the actual file over viewing page content
                - If page is in a non-English language, still identify form fields by their input attributes
                """);

        return goal.toString();
    }

    /**
     * Resolves ALL placeholder types to actual values.
     * Supports every portal type: airlines, e-commerce, banks, government, utilities, insurance.
     * Mapping: username → name/surname fields, password → email/password/phone, reference → any ID/number.
     */
    private String resolveValue(String value, String username, String password, String reference) {
        if (value == null) return "";

        String safeUser = username != null ? username : "";
        String safePass = password != null ? password : "";
        String safeRef  = reference != null ? reference : "";
        boolean passIsEmail = safePass.contains("@");

        String resolved = value
                // Identity / name fields → username
                .replace("{{username}}", safeUser)
                .replace("{{lastname}}", safeUser)
                .replace("{{last_name}}", safeUser)
                .replace("{{surname}}", safeUser)
                .replace("{{name}}", safeUser)
                .replace("{{first_name}}", safeUser)
                .replace("{{full_name}}", safeUser)
                // Auth fields → password
                .replace("{{email}}", passIsEmail ? safePass : safeUser.contains("@") ? safeUser : "")
                .replace("{{password}}", safePass)
                .replace("{{pin}}", safePass)
                .replace("{{mpin}}", safePass)
                .replace("{{otp}}", "")
                // Contact fields → password (often stores phone/email)
                .replace("{{mobile}}", safePass.replaceAll("[^0-9+]", "").length() >= 10 ? safePass : "")
                .replace("{{phone}}", safePass.replaceAll("[^0-9+]", "").length() >= 10 ? safePass : "")
                // Reference / ID fields → reference (PNR, order ID, Aadhaar, PAN, account, etc.)
                .replace("{{reference}}", safeRef)
                .replace("{{pnr}}", safeRef)
                .replace("{{order_id}}", safeRef)
                .replace("{{order}}", safeRef)
                .replace("{{invoice}}", safeRef)
                .replace("{{invoice_number}}", safeRef)
                .replace("{{booking_id}}", safeRef)
                .replace("{{booking}}", safeRef)
                .replace("{{ticket}}", safeRef)
                // Government ID fields → reference
                .replace("{{aadhaar}}", safeRef)
                .replace("{{aadhar}}", safeRef)
                .replace("{{pan}}", safeRef)
                .replace("{{pan_number}}", safeRef)
                .replace("{{voter_id}}", safeRef)
                .replace("{{driving_license}}", safeRef)
                .replace("{{passport}}", safeRef)
                .replace("{{gstin}}", safeRef)
                .replace("{{gst_number}}", safeRef)
                .replace("{{din}}", safeRef)
                .replace("{{cin}}", safeRef)
                .replace("{{uan}}", safeRef)
                // Bank / financial fields → reference
                .replace("{{account_number}}", safeRef)
                .replace("{{account}}", safeRef)
                .replace("{{ifsc}}", safeRef)
                .replace("{{card_number}}", safeRef)
                .replace("{{loan_number}}", safeRef)
                .replace("{{folio}}", safeRef)
                // Utility fields → reference
                .replace("{{consumer_number}}", safeRef)
                .replace("{{consumer}}", safeRef)
                .replace("{{meter_number}}", safeRef)
                .replace("{{bill_number}}", safeRef)
                .replace("{{connection_id}}", safeRef)
                .replace("{{ca_number}}", safeRef)
                // Insurance fields → reference
                .replace("{{policy_number}}", safeRef)
                .replace("{{policy}}", safeRef)
                .replace("{{claim_number}}", safeRef)
                // Personal fields → reference or username
                .replace("{{dob}}", safeRef)
                .replace("{{date_of_birth}}", safeRef)
                .replace("{{father_name}}", safeUser)
                .replace("{{spouse_name}}", safeUser);

        if (resolved.contains("{{")) {
            log.warn("[Dynamic] Unresolved placeholder in value: {}", resolved);
        }
        return resolved;
    }

    private boolean hasVisibleDownloadElements(Page page) {
        String[] downloadSelectors = {
                "a:has-text('Download')", "button:has-text('Download')",
                "a[href$='.pdf']", "a[href*='download']",
                "a:has-text('Get Your GST')", "a:has-text('Save')",
                "[aria-label*='download' i]", "[title*='download' i]",
                "a:has-text('Export')", "button:has-text('Export')",
                "a:has-text('Print')", "button:has-text('Print')",
                "svg[class*='download']", "img[alt*='download' i]"
        };
        for (String sel : downloadSelectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    log.info("[Dynamic] Found visible download element: {} (count={})", sel, loc.count());
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isOtpPage(Page page) {
        try {
            return page.locator(String.join(", ",
                    "input[placeholder*='OTP' i]",
                    "input[placeholder*='otp' i]",
                    "input[placeholder*='verification' i]",
                    "input[name*='otp' i]",
                    "input[type='tel'][maxlength='6']",
                    "input[type='tel'][maxlength='4']"
            )).count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void fillOtp(Page page, String otp) {
        String[] selectors = {
                "input[placeholder*='OTP' i]",
                "input[name*='otp' i]",
                "input[type='tel'][maxlength='6']",
                "input[type='tel'][maxlength='4']",
                "input[placeholder*='verification' i]"
        };
        for (String selector : selectors) {
            try {
                if (page.locator(selector).count() > 0) {
                    page.fill(selector, otp);
                    log.info("[Dynamic] OTP filled via: {}", selector);
                    break;
                }
            } catch (Exception ignored) {}
        }
        try {
            page.click("button[type='submit'], button:has-text('Verify'), " +
                    "button:has-text('Submit'), button:has-text('Continue')");
        } catch (Exception e) {
            log.warn("[Dynamic] Could not auto-click OTP submit button");
        }
    }

    private static final long MIN_DOCUMENT_SIZE_BYTES = 1024; // 1KB minimum for a real document

    private File executeDownload(Page page, String selector, String portalName,
                                 String documentType, String reference) {
        String downloadDir = Paths.get(storagePath, "temp").toString();
        new File(downloadDir).mkdirs();
        String ref = reference != null ? reference : "unknown";
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String urlBefore = page.url();

        // SINGLE CLICK — then detect what happened (popup / download / navigation)
        final java.util.concurrent.atomic.AtomicReference<Page> popupRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Download> downloadRef = new java.util.concurrent.atomic.AtomicReference<>();

        // Register listeners BEFORE clicking
        page.onPopup(popupRef::set);
        page.onDownload(downloadRef::set);

        try {
            page.locator(selector).first().click(new Locator.ClickOptions().setTimeout(3000));
            log.info("[Dynamic] Clicked '{}' — waiting for response...", selector);
        } catch (Exception e) {
            log.debug("[Dynamic] Click failed for '{}': {}", selector, e.getMessage());
            page.onPopup(p -> {}); page.onDownload(d -> {});
            return null;
        }

        // Wait for events to fire
        waitSafe(page, 3000);

        // Remove listeners
        page.onPopup(p -> {}); page.onDownload(d -> {});

        // === Check 1: Was a file download triggered? ===
        Download download = downloadRef.get();
        if (download != null) {
            try {
                String fileName = download.suggestedFilename();
                if (fileName == null || fileName.isBlank()) {
                    fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
                }
                String fnLower = fileName.toLowerCase();
                if (isIrrelevantDownload(fnLower, documentType)) {
                    log.warn("[Dynamic] Downloaded file '{}' is NOT relevant for '{}' — skipping", fileName, documentType);
                    download.delete();
                    return null;
                }
                Path savePath = Paths.get(downloadDir, fileName);
                download.saveAs(savePath);
                if (savePath.toFile().length() >= MIN_DOCUMENT_SIZE_BYTES) {
                    log.info("[Dynamic] File downloaded: {} ({}KB)", savePath, savePath.toFile().length() / 1024);
                    return savePath.toFile();
                }
                log.warn("[Dynamic] Downloaded file too small ({}B)", savePath.toFile().length());
                savePath.toFile().delete();
            } catch (Exception e) {
                log.debug("[Dynamic] Download save failed: {}", e.getMessage());
            }
        }

        // === Check 2: Did a popup/new tab open? ===
        Page popup = popupRef.get();
        if (popup != null) {
            log.info("[Dynamic] Popup detected: {}", popup.url());
            String popupUrlLower = popup.url().toLowerCase();
            if (isIrrelevantDownload(popupUrlLower, documentType)) {
                log.warn("[Dynamic] Popup URL '{}' is NOT relevant for '{}' — closing", popup.url(), documentType);
                popup.close();
                return null;
            }
            File result = processNewTab(popup, portalName, documentType, ref, uid, downloadDir);
            if (result != null) return result;
        }

        // Also check for new tabs via context (some popups bypass the event)
        try {
            var allPages = page.context().pages();
            if (allPages.size() > 1) {
                Page newTab = allPages.get(allPages.size() - 1);
                if (popup == null || !newTab.url().equals(popup.url())) {
                    log.info("[Dynamic] Extra tab found: {}", newTab.url());
                    File result = processNewTab(newTab, portalName, documentType, ref, uid, downloadDir);
                    if (result != null) return result;
                }
            }
        } catch (Exception ignored) {}

        // === Check 3: Did the main page navigate to a new URL? ===
        String urlAfter = page.url();
        if (!urlAfter.equals(urlBefore)) {
            log.info("[Dynamic] Page navigated: {} → {}", urlBefore, urlAfter);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitSafe(page, 2000);

            String fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
            File pdfFile = printPageToPdf(page, fileName, downloadDir);
            if (pdfFile != null) {
                log.info("[Dynamic] Navigated page saved as PDF: {} ({}KB)", pdfFile.getPath(), pdfFile.length() / 1024);
                return pdfFile;
            }
        }

        return null;
    }

    /**
     * Saves a page tab as a proper PDF document.
     * Validates the page has actual document content (not just a site shell or error page).
     */
    private File saveTabAsPdf(Page tabPage, String portalName, String documentType,
                              String ref, String uid, String downloadDir) {
        try {
            String url = tabPage.url().toLowerCase();

            // Only reject truly empty/invalid pages
            if (url.equals("about:blank") || url.contains("404") || url.contains("chrome-error")) {
                log.warn("[Dynamic] Tab is blank/error (url='{}'), skipping", url);
                return null;
            }

            String fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
            return printPageToPdf(tabPage, fileName, downloadDir);
        } catch (Exception e) {
            log.warn("[Dynamic] Failed to save tab as PDF: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Checks if a downloaded file/URL is NOT relevant to the requested document type.
     * e.g., a "Tariff Sheet" PDF is irrelevant when the user asked for an "invoice".
     * Returns true if the download should be SKIPPED.
     */
    private boolean isIrrelevantDownload(String fileOrUrl, String requestedDocType) {
        String type = requestedDocType.toLowerCase();
        String source = fileOrUrl.toLowerCase();

        // Tariff sheets are only relevant when explicitly requested
        if (source.contains("tariff") && !type.contains("tariff")) return true;
        // Terms/privacy/brochure are never the target document
        if (source.contains("terms-and-conditions") || source.contains("privacy-policy") ||
                source.contains("brochure") || source.contains("conditions-of-carriage")) return true;

        return false;
    }

    /**
     * Processes a newly opened tab: tries native download first, then saves as PDF via CDP.
     * Always closes the tab after processing.
     */
    private File processNewTab(Page newTab, String portalName, String documentType,
                               String ref, String uid, String downloadDir) {
        try {
            newTab.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitSafe(newTab, 2000);
            String url = newTab.url();
            String title = newTab.title();
            log.info("[Dynamic] Processing new tab: url='{}', title='{}'", url, title);

            // Skip truly blank/error pages
            if (url.equals("about:blank") || url.contains("chrome-error")) {
                log.warn("[Dynamic] Tab is blank/error, closing");
                newTab.close();
                return null;
            }

            // Try native download from the tab (for direct PDF URLs)
            if (url.toLowerCase().contains(".pdf") || url.toLowerCase().contains("download")) {
                try {
                    final Page tab = newTab;
                    Download dl = tab.waitForDownload(
                            new Page.WaitForDownloadOptions().setTimeout(3000),
                            () -> tab.reload(new Page.ReloadOptions().setTimeout(5000)));
                    String fileName = dl.suggestedFilename();
                    if (fileName == null || fileName.isBlank())
                        fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
                    Path savePath = Paths.get(downloadDir, fileName);
                    dl.saveAs(savePath);
                    if (savePath.toFile().length() >= MIN_DOCUMENT_SIZE_BYTES) {
                        log.info("[Dynamic] Native download from new tab: {} ({}KB)", savePath, savePath.toFile().length() / 1024);
                        newTab.close();
                        return savePath.toFile();
                    }
                    savePath.toFile().delete();
                } catch (Exception ignored) {}
            }

            // Save the tab as PDF using CDP printToPDF
            String fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
            File pdfFile = printPageToPdf(newTab, fileName, downloadDir);
            newTab.close();
            if (pdfFile != null) return pdfFile;
        } catch (Exception e) {
            log.warn("[Dynamic] processNewTab failed: {}", e.getMessage());
            try { newTab.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Generates a PDF from a live page using Chrome DevTools Protocol (CDP).
     * Works in HEADED mode — no need for headless browser.
     */
    private File printPageToPdf(Page targetPage, String fileName, String downloadDir) {
        try {
            // Hide non-document elements for a clean invoice PDF
            try {
                targetPage.evaluate("""
                    (() => {
                        const hide = ['header', 'footer', 'nav', '.headerv2', '.footer',
                                      '[role="navigation"]', '.cookie-banner', '.chat-widget',
                                      '.sidebar', '.need-help', '.chatbot'];
                        hide.forEach(sel => {
                            document.querySelectorAll(sel).forEach(el => el.style.display = 'none');
                        });
                    })()
                """);
            } catch (Exception ignored) {}

            CDPSession cdp = targetPage.context().newCDPSession(targetPage);
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.addProperty("landscape", false);
            params.addProperty("printBackground", true);
            params.addProperty("paperWidth", 8.27);
            params.addProperty("paperHeight", 11.69);
            params.addProperty("marginTop", 0.4);
            params.addProperty("marginBottom", 0.4);
            params.addProperty("marginLeft", 0.4);
            params.addProperty("marginRight", 0.4);

            // Timeout wrapper — CDP printToPDF can hang on complex pages
            java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> pdfFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> cdp.send("Page.printToPDF", params));

            com.google.gson.JsonObject result;
            try {
                result = pdfFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                log.warn("[Dynamic] CDP printToPDF timed out after 15s");
                try { cdp.detach(); } catch (Exception ignored) {}
                return null;
            }

            String base64Data = result.get("data").getAsString();
            byte[] pdfBytes = Base64.getDecoder().decode(base64Data);
            try { cdp.detach(); } catch (Exception ignored) {}

            Path savePath = Paths.get(downloadDir, fileName);
            java.nio.file.Files.write(savePath, pdfBytes);

            long fileSize = savePath.toFile().length();
            if (fileSize >= MIN_DOCUMENT_SIZE_BYTES) {
                log.info("[Dynamic] CDP printToPDF success: {} ({}KB)", savePath, fileSize / 1024);
                return savePath.toFile();
            }
            log.warn("[Dynamic] CDP PDF too small ({}B)", fileSize);
            savePath.toFile().delete();
        } catch (Exception e) {
            log.warn("[Dynamic] CDP printToPDF failed: {}", e.getMessage());
        }
        return null;
    }

    private File tryAutoDownload(Page page, String portalName, String documentType, String reference) {
        boolean isImageRequest = isImageDocType(documentType);

        // Priority order: exact download links first, then broader matches
        String[] primarySelectors = {
                "a:has-text('Download')", "button:has-text('Download')",
                "a[href$='.pdf']", "a[href*='download']", "a[href*='invoice']",
                "a:has-text('Get Your GST')", "a:has-text('Save')",
                "button:has-text('Save')", "button:has-text('Export')",
                "[aria-label*='download' i]", "[title*='download' i]"
        };
        String[] secondarySelectors = {
                "a:has-text('GST Invoice')", "a:has-text('Invoice')",
                "a:has-text('Boarding Pass')", "a:has-text('E-Ticket')",
                "a:has-text('Itinerary')", "a:has-text('Receipt')",
                "[data-testid*='download' i]", ".download", "[class*='download']"
        };

        // Image-specific selectors for wallpaper/image sites
        String[] imageSelectors = {
                "a:has-text('Download free')", "a:has-text('Download')",
                "button:has-text('Download')", "a:has-text('Free Download')",
                "a[download]", "a[href$='.jpg']", "a[href$='.jpeg']",
                "a[href$='.png']", "a[href$='.webp']",
                "a[href*='download']", "a[href*='photos']",
                "[data-testid*='download']", "button:has-text('Save')"
        };

        if (isImageRequest) {
            // For image requests, try image selectors first
            File result = trySelectorsForDownload(page, imageSelectors, 5, portalName, documentType, reference);
            if (result != null) return result;

            // Fallback: try to save the largest visible image directly
            result = saveDirectImage(page, portalName, documentType, reference);
            if (result != null) return result;
        }

        // Try primary (download-specific) selectors — up to 5 attempts
        File result = trySelectorsForDownload(page, primarySelectors, 5, portalName, documentType, reference);
        if (result != null) return result;

        // Then try secondary (document-name) selectors — up to 3 more attempts
        if (!isImageRequest) {
            result = trySelectorsForDownload(page, secondarySelectors, 3, portalName, documentType, reference);
            if (result != null) return result;
        }

        log.warn("[Dynamic] Auto-download: no download elements found on page");
        return null;
    }

    /**
     * Detects which user-facing fields are missing from a placeholder string.
     * Works for ALL portal types: airlines, e-commerce, government, banks, utilities, insurance.
     */
    private List<String> detectMissingFields(String rawValue, String fallbackFieldName) {
        List<String> fields = new ArrayList<>();
        String rv = rawValue.toLowerCase();

        // Auth / credential fields
        if (rv.contains("{{password}}") || rv.contains("{{pin}}") || rv.contains("{{mpin}}"))
            fields.add("password / PIN");
        if (rv.contains("{{email}}"))
            fields.add("email address");
        if (rv.contains("{{mobile}}") || rv.contains("{{phone}}"))
            fields.add("mobile / phone number");

        // Identity fields
        if (rv.contains("{{username}}") || rv.contains("{{lastname}}") || rv.contains("{{last_name}}") ||
                rv.contains("{{surname}}") || rv.contains("{{name}}") || rv.contains("{{first_name}}") ||
                rv.contains("{{full_name}}") || rv.contains("{{father_name}}") || rv.contains("{{spouse_name}}"))
            fields.add("name / last name");

        // Reference / booking IDs
        if (rv.contains("{{reference}}") || rv.contains("{{pnr}}") || rv.contains("{{booking_id}}") ||
                rv.contains("{{booking}}") || rv.contains("{{ticket}}"))
            fields.add("PNR / booking reference");
        if (rv.contains("{{order_id}}") || rv.contains("{{order}}") || rv.contains("{{invoice}}") ||
                rv.contains("{{invoice_number}}"))
            fields.add("order ID / invoice number");

        // Government IDs
        if (rv.contains("{{aadhaar}}") || rv.contains("{{aadhar}}"))
            fields.add("Aadhaar number");
        if (rv.contains("{{pan}}") || rv.contains("{{pan_number}}"))
            fields.add("PAN number");
        if (rv.contains("{{voter_id}}"))
            fields.add("Voter ID");
        if (rv.contains("{{driving_license}}"))
            fields.add("Driving License number");
        if (rv.contains("{{passport}}"))
            fields.add("Passport number");
        if (rv.contains("{{gstin}}") || rv.contains("{{gst_number}}"))
            fields.add("GSTIN");
        if (rv.contains("{{uan}}"))
            fields.add("UAN (Universal Account Number)");

        // Bank / financial
        if (rv.contains("{{account_number}}") || rv.contains("{{account}}"))
            fields.add("account number");
        if (rv.contains("{{ifsc}}"))
            fields.add("IFSC code");
        if (rv.contains("{{card_number}}"))
            fields.add("card number");
        if (rv.contains("{{loan_number}}"))
            fields.add("loan account number");
        if (rv.contains("{{folio}}"))
            fields.add("folio number");

        // Utility
        if (rv.contains("{{consumer_number}}") || rv.contains("{{consumer}}"))
            fields.add("consumer number");
        if (rv.contains("{{meter_number}}"))
            fields.add("meter number");
        if (rv.contains("{{bill_number}}"))
            fields.add("bill number");
        if (rv.contains("{{connection_id}}") || rv.contains("{{ca_number}}"))
            fields.add("connection / CA number");

        // Insurance
        if (rv.contains("{{policy_number}}") || rv.contains("{{policy}}"))
            fields.add("policy number");
        if (rv.contains("{{claim_number}}"))
            fields.add("claim number");

        // Personal
        if (rv.contains("{{dob}}") || rv.contains("{{date_of_birth}}"))
            fields.add("date of birth");

        if (fields.isEmpty()) fields.add(fallbackFieldName);
        return fields;
    }

    /**
     * Detects needed fields from LLM's "stuck" reasoning text.
     * Covers all portal types: e-commerce, government, banks, utilities, insurance, telecom.
     */
    private List<String> detectMissingFieldsFromReasoning(String reason) {
        List<String> fields = new ArrayList<>();

        // Auth
        if (reason.contains("password") || reason.contains("pin")) fields.add("password / PIN");
        if (reason.contains("email")) fields.add("email address");
        if (reason.contains("login") || reason.contains("credential") || reason.contains("sign in") ||
                reason.contains("sign-in") || reason.contains("authenticate"))
            fields.add("login credentials");
        if (reason.contains("mobile") || reason.contains("phone")) fields.add("mobile / phone number");

        // Identity
        if (reason.contains("username") || reason.contains("last name") || reason.contains("surname") ||
                reason.contains("family name") || (reason.contains("name") && !reason.contains("username")))
            fields.add("name / last name");

        // References
        if (reason.contains("pnr") || reason.contains("booking") || reason.contains("reservation"))
            fields.add("PNR / booking reference");
        if (reason.contains("order") || reason.contains("invoice")) fields.add("order ID / invoice number");
        if (reason.contains("reference") || reason.contains("tracking")) fields.add("reference / tracking number");

        // Government IDs
        if (reason.contains("aadhaar") || reason.contains("aadhar") || reason.contains("uid"))
            fields.add("Aadhaar number");
        if (reason.contains("pan")) fields.add("PAN number");
        if (reason.contains("voter")) fields.add("Voter ID");
        if (reason.contains("driving") || reason.contains("license")) fields.add("Driving License number");
        if (reason.contains("passport") && !reason.contains("password")) fields.add("Passport number");
        if (reason.contains("gstin") || reason.contains("gst number") || reason.contains("gst no"))
            fields.add("GSTIN");
        if (reason.contains("uan")) fields.add("UAN");

        // Bank
        if (reason.contains("account number") || reason.contains("account no")) fields.add("account number");
        if (reason.contains("ifsc")) fields.add("IFSC code");
        if (reason.contains("card number") || reason.contains("debit card") || reason.contains("credit card"))
            fields.add("card number");

        // Utility
        if (reason.contains("consumer number") || reason.contains("consumer no") || reason.contains("consumer id"))
            fields.add("consumer number");
        if (reason.contains("meter")) fields.add("meter number");
        if (reason.contains("connection")) fields.add("connection / CA number");

        // Insurance
        if (reason.contains("policy")) fields.add("policy number");
        if (reason.contains("claim")) fields.add("claim number");

        // Personal
        if (reason.contains("date of birth") || reason.contains("dob") || reason.contains("birth date"))
            fields.add("date of birth");

        // Verification
        if (reason.contains("otp")) fields.add("OTP verification");
        if (reason.contains("captcha") || reason.contains("recaptcha")) fields.add("CAPTCHA verification");

        return fields;
    }

    private boolean isImageDocType(String documentType) {
        if (documentType == null) return false;
        String dt = documentType.toLowerCase();
        return dt.contains("wallpaper") || dt.contains("image") || dt.contains("photo") || dt.contains("picture");
    }

    private boolean isCheckInDocType(String documentType) {
        if (documentType == null) return false;
        String dt = documentType.toLowerCase();
        return dt.contains("boardingpass") || dt.contains("boarding") || dt.contains("checkin") || dt.contains("check-in");
    }

    /**
     * Finds the largest visible image on the page and downloads it directly via its URL.
     * Used as fallback when no download button is found for image/wallpaper requests.
     */
    private File saveDirectImage(Page page, String portalName, String documentType, String reference) {
        try {
            String downloadDir = Paths.get(storagePath, "temp").toString();
            new File(downloadDir).mkdirs();
            String ref = reference != null ? reference.replaceAll("[^a-zA-Z0-9_-]", "_") : "unknown";
            String uid = UUID.randomUUID().toString().substring(0, 8);

            // Find the largest image on page (by natural dimensions, not display size)
            Object imgResult = page.evaluate("""
                (() => {
                    const imgs = Array.from(document.querySelectorAll('img[src]'));
                    let best = null;
                    let bestArea = 0;
                    for (const img of imgs) {
                        const w = img.naturalWidth || img.width;
                        const h = img.naturalHeight || img.height;
                        const area = w * h;
                        const src = img.src || '';
                        if (area > bestArea && area > 10000 && !src.includes('logo') &&
                            !src.includes('icon') && !src.includes('avatar') &&
                            !src.includes('sprite') && !src.includes('data:image/svg')) {
                            bestArea = area;
                            best = { src: src, width: w, height: h };
                        }
                    }
                    return best;
                })()
            """);

            if (imgResult == null) {
                log.info("[Dynamic] No suitable image found on page for direct download");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> imgInfo = (Map<String, Object>) imgResult;
            String imgUrl = (String) imgInfo.get("src");
            int imgW = ((Number) imgInfo.get("width")).intValue();
            int imgH = ((Number) imgInfo.get("height")).intValue();
            log.info("[Dynamic] Found image: {}x{} — {}", imgW, imgH, imgUrl.substring(0, Math.min(100, imgUrl.length())));

            // Determine extension from URL
            String ext = ".jpg";
            String urlLower = imgUrl.toLowerCase();
            if (urlLower.contains(".png")) ext = ".png";
            else if (urlLower.contains(".webp")) ext = ".webp";
            else if (urlLower.contains(".jpeg")) ext = ".jpg";

            // Download the image via CDP network fetch
            String fileName = "%s_%s_%s_%s%s".formatted(portalName, documentType, ref, uid, ext);
            Path savePath = Paths.get(downloadDir, fileName);

            // Use Java HTTP to fetch the image (simpler and more reliable)
            java.net.URL url = new java.net.URL(imgUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", page.url());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            try (java.io.InputStream in = conn.getInputStream()) {
                java.nio.file.Files.copy(in, savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            conn.disconnect();

            long fileSize = savePath.toFile().length();
            if (fileSize >= MIN_DOCUMENT_SIZE_BYTES) {
                log.info("[Dynamic] Image downloaded directly: {} ({}KB, {}x{})", savePath, fileSize / 1024, imgW, imgH);
                return savePath.toFile();
            }
            log.warn("[Dynamic] Downloaded image too small ({}B)", fileSize);
            savePath.toFile().delete();
        } catch (Exception e) {
            log.warn("[Dynamic] Direct image download failed: {}", e.getMessage());
        }
        return null;
    }

    private File trySelectorsForDownload(Page page, String[] selectors, int maxAttempts,
                                         String portalName, String documentType, String reference) {
        int attempts = 0;
        for (String selector : selectors) {
            if (attempts >= maxAttempts) break;
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    // Skip elements that are in nav/header (likely site navigation, not document downloads)
                    try {
                        String parentTag = loc.first().evaluate("el => { let p = el.closest('nav, header, [role=\"navigation\"]'); return p ? p.tagName : ''; }").toString();
                        if (!parentTag.isEmpty()) {
                            log.debug("[Dynamic] Skipping nav/header element: {} (parent: {})", selector, parentTag);
                            continue;
                        }
                    } catch (Exception ignored) {}

                    attempts++;
                    log.info("[Dynamic] Auto-download trying: {} ({} visible)", selector, loc.count());
                    File result = executeDownload(page, selector, portalName, documentType, reference);
                    if (result != null) return result;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private File savePageAsPdf(Page page, String portalName, String documentType,
                               String reference, String debugDir) {
        try {
            // Close any extra tabs first to stabilize the context
            try {
                var allPages = page.context().pages();
                for (int i = allPages.size() - 1; i > 0; i--) {
                    try { allPages.get(i).close(); } catch (Exception ignored) {}
                }
                waitSafe(page, 500);
            } catch (Exception ignored) {}

            // For image/wallpaper requests, save the largest image instead of PDF
            if (isImageDocType(documentType)) {
                log.info("[Dynamic] Image request — saving largest image from page instead of PDF");
                File imgFile = saveDirectImage(page, portalName, documentType, reference);
                if (imgFile != null) return imgFile;
                log.info("[Dynamic] No image found — falling back to screenshot as PNG");
                return savePageAsScreenshot(page, portalName, documentType, reference);
            }

            String downloadDir = Paths.get(storagePath, "temp").toString();
            new File(downloadDir).mkdirs();
            String uid = UUID.randomUUID().toString().substring(0, 8);
            String ref = reference != null ? reference : "unknown";

            String pageTitle = "";
            try { pageTitle = page.title(); } catch (Exception ignored) {}

            String fileName = "%s_%s_%s_%s.pdf".formatted(portalName, documentType, ref, uid);
            File pdfFile = printPageToPdf(page, fileName, downloadDir);
            if (pdfFile != null) {
                log.info("[Dynamic] Page saved as PDF via CDP: {} ({}KB) [title='{}']", pdfFile.getPath(), pdfFile.length() / 1024, pageTitle);
            }
            return pdfFile;
        } catch (Exception e) {
            log.warn("[Dynamic] Page save as PDF failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Screenshot fallback for image requests — when no downloadable image is found,
     * take a full-page screenshot as a last resort.
     */
    private File savePageAsScreenshot(Page page, String portalName, String documentType, String reference) {
        try {
            String downloadDir = Paths.get(storagePath, "temp").toString();
            new File(downloadDir).mkdirs();
            String ref = reference != null ? reference.replaceAll("[^a-zA-Z0-9_-]", "_") : "unknown";
            String uid = UUID.randomUUID().toString().substring(0, 8);
            String fileName = "%s_%s_%s_%s.png".formatted(portalName, documentType, ref, uid);
            Path savePath = Paths.get(downloadDir, fileName);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(savePath)
                    .setFullPage(true));

            long fileSize = savePath.toFile().length();
            if (fileSize >= MIN_DOCUMENT_SIZE_BYTES) {
                log.info("[Dynamic] Page screenshot saved: {} ({}KB)", savePath, fileSize / 1024);
                return savePath.toFile();
            }
            savePath.toFile().delete();
        } catch (Exception e) {
            log.warn("[Dynamic] Screenshot save failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Saves the steps taken so far as a partial (unverified) workflow.
     * Called when MissingDataException is thrown, so the next attempt can replay
     * these steps and resume from where credentials were missing — instead of starting over.
     */
    private void savePartialWorkflow(String portalName, String documentType, String startUrl) {
        try {
            List<PortalAdapter.NavigationStep> steps = PortalAdapter.StepRecorder.getSteps();
            if (steps.isEmpty()) return;

            String cssSelectors = steps.stream()
                    .filter(s -> s.selector() != null || "navigate".equals(s.action()))
                    .map(s -> {
                        if ("navigate".equals(s.action())) return "navigate:" + s.url();
                        if ("fill".equals(s.action()) && s.description() != null && s.description().startsWith("val=")) {
                            return "fill:" + s.selector() + "==" + s.description().substring(4);
                        }
                        return s.action() + ":" + s.selector();
                    })
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(null);

            if (cssSelectors != null) {
                memoryService.saveWorkflow(portalName, documentType, startUrl,
                        "Partial — needs credentials to continue", cssSelectors, false);
                log.info("[Dynamic] Saved PARTIAL workflow ({} steps) for resume on retry: portal={}, type={}",
                        steps.size(), portalName, documentType);
            }
        } catch (Exception ex) {
            log.warn("[Dynamic] Failed to save partial workflow: {}", ex.getMessage());
        }
    }

    private void saveLearnedWorkflow(String portalName, String documentType,
                                     String startUrl, List<String> actionHistory) {
        try {
            String steps = String.join(" → ", actionHistory);
            List<PortalAdapter.NavigationStep> navSteps = PortalAdapter.StepRecorder.getSteps();
            // Format: action:selector or fill:selector=={{valueType}}
            String cssSelectors = navSteps.stream()
                    .filter(s -> s.selector() != null || "navigate".equals(s.action()))
                    .map(s -> {
                        if ("navigate".equals(s.action())) return "navigate:" + s.url();
                        if ("fill".equals(s.action()) && s.description() != null && s.description().startsWith("val=")) {
                            String valType = s.description().substring(4);
                            return "fill:" + s.selector() + "==" + valType;
                        }
                        return s.action() + ":" + s.selector();
                    })
                    .reduce((a, b) -> a + " | " + b)
                    .orElse(null);
            memoryService.saveWorkflow(portalName, documentType, startUrl, steps, cssSelectors, true);
            log.info("[Dynamic] Workflow LEARNED and saved: portal={}, type={}, steps={}, cssSelectors={}",
                    portalName, documentType, actionHistory.size(), cssSelectors);
        } catch (Exception e) {
            log.warn("[Dynamic] Failed to save workflow: {}", e.getMessage());
        }
    }

    private File replayCachedWorkflow(Page page, browserAI.demo.model.entity.PortalWorkflow workflow,
                                      String username, String password, String reference,
                                      String portalName, String documentType, String startUrl,
                                      String debugDir) {
        try {
            String cssSelectors = workflow.getCssSelectors();
            if (cssSelectors == null || cssSelectors.isBlank()) return null;

            String[] stepParts = cssSelectors.split("\\s*\\|\\s*");
            page.navigate(workflow.getLoginUrl() != null ? workflow.getLoginUrl() : startUrl);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitSafe(page, 2000);

            for (String stepStr : stepParts) {
                stepStr = stepStr.trim();
                if (stepStr.isBlank()) continue;

                String[] actionAndRest = stepStr.split(":", 2);
                if (actionAndRest.length < 2) continue;

                String action = actionAndRest[0].trim().toLowerCase();
                String selectorAndValue = actionAndRest[1].trim();
                log.info("[Dynamic:Replay] {} -> {}", action, selectorAndValue);

                switch (action) {
                    case "click" -> {
                        try {
                            page.click(selectorAndValue, new Page.ClickOptions().setTimeout(8000));
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 1500);
                        } catch (Exception e) {
                            log.warn("[Dynamic:Replay] Click failed: {}", e.getMessage());
                            return null;
                        }
                    }
                    case "fill" -> {
                        String selector = selectorAndValue;
                        String valueTemplate;
                        if (selectorAndValue.contains("==")) {
                            String[] parts = selectorAndValue.split("==", 2);
                            selector = parts[0].trim();
                            valueTemplate = parts[1].trim();
                        } else {
                            valueTemplate = guessFillValueType(selector);
                        }
                        String value = resolveValue(valueTemplate, username, password, reference);
                        log.info("[Dynamic:Replay] fill {} with template {}", selector, valueTemplate);
                        try {
                            page.fill(selector, value);
                        } catch (Exception e) {
                            log.warn("[Dynamic:Replay] Fill failed: {}", e.getMessage());
                            return null;
                        }
                    }
                    case "navigate" -> {
                        try {
                            page.navigate(selectorAndValue);
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 1500);
                        } catch (Exception e) {
                            log.warn("[Dynamic:Replay] Navigate failed: {}", e.getMessage());
                            return null;
                        }
                    }
                    case "download" -> {
                        File file = executeDownload(page, selectorAndValue, portalName, documentType, reference);
                        if (file != null) return file;
                        return null;
                    }
                    default -> log.debug("[Dynamic:Replay] Skipping: {}", action);
                }
            }
            return tryAutoDownload(page, portalName, documentType, reference);
        } catch (Exception e) {
            log.warn("[Dynamic:Replay] Replay failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Guesses the placeholder type from a CSS selector during workflow replay.
     * Covers all portal types so cached steps resolve correctly on retry.
     */
    private String guessFillValueType(String selector) {
        String s = selector.toLowerCase();
        // Auth fields
        if (s.contains("password") || s.contains("pin") || s.contains("mpin")) return "{{password}}";
        if (s.contains("email")) return "{{email}}";
        if (s.contains("mobile") || s.contains("phone") || s.contains("tel")) return "{{mobile}}";
        // Name fields
        if (s.contains("last") || s.contains("surname") || s.contains("family")) return "{{username}}";
        if (s.contains("user") || s.contains("name") || s.contains("first")) return "{{username}}";
        // Government IDs
        if (s.contains("aadhaar") || s.contains("aadhar") || s.contains("uid")) return "{{aadhaar}}";
        if (s.contains("pan")) return "{{pan}}";
        if (s.contains("gstin") || s.contains("gst")) return "{{gstin}}";
        if (s.contains("voter")) return "{{voter_id}}";
        if (s.contains("passport")) return "{{passport}}";
        if (s.contains("driving") || s.contains("license") || s.contains("dl")) return "{{driving_license}}";
        if (s.contains("uan")) return "{{uan}}";
        // Bank fields
        if (s.contains("account") || s.contains("acc")) return "{{account_number}}";
        if (s.contains("ifsc")) return "{{ifsc}}";
        if (s.contains("card")) return "{{card_number}}";
        if (s.contains("loan")) return "{{loan_number}}";
        // Utility fields
        if (s.contains("consumer")) return "{{consumer_number}}";
        if (s.contains("meter")) return "{{meter_number}}";
        if (s.contains("bill")) return "{{bill_number}}";
        if (s.contains("connection") || s.contains("ca_")) return "{{connection_id}}";
        // Insurance fields
        if (s.contains("policy")) return "{{policy_number}}";
        if (s.contains("claim")) return "{{claim_number}}";
        // Booking fields
        if (s.contains("pnr")) return "{{pnr}}";
        if (s.contains("order")) return "{{order_id}}";
        if (s.contains("invoice")) return "{{invoice_number}}";
        if (s.contains("booking") || s.contains("ticket")) return "{{booking_id}}";
        // Personal
        if (s.contains("dob") || s.contains("birth")) return "{{dob}}";
        // Default
        return "{{reference}}";
    }

    /**
     * Replays the common prefix steps from a sibling workflow (same portal, different document type).
     * Skips the terminal step (download) so that LLM exploration can continue from the
     * form-submitted state, saving tokens for the shared navigation/fill steps.
     *
     * Example: IndiGo "invoice" workflow = navigate → fill PNR → fill email → click submit → download
     * For a "gstinformation" request, this replays: navigate → fill PNR → fill email → click submit
     * Then LLM takes over to find the GST information section instead of the download button.
     *
     * @return true if prefix was replayed successfully, false otherwise
     */
    /**
     * Replays common prefix from a sibling workflow. Returns true if at least navigate +
     * form fills succeeded, even if a later click step fails. This way the LLM takes over
     * from a pre-filled form state instead of starting from scratch.
     */
    private boolean replayCommonPrefix(Page page, browserAI.demo.model.entity.PortalWorkflow siblingWorkflow,
                                       String username, String password, String reference,
                                       String startUrl, String debugDir) {
        try {
            String cssSelectors = siblingWorkflow.getCssSelectors();
            if (cssSelectors == null || cssSelectors.isBlank()) return false;

            String[] stepParts = cssSelectors.split("\\s*\\|\\s*");

            List<String[]> prefixSteps = new ArrayList<>();
            for (String stepStr : stepParts) {
                stepStr = stepStr.trim();
                if (stepStr.isBlank()) continue;
                String[] actionAndSelector = stepStr.split(":", 2);
                if (actionAndSelector.length < 2) continue;
                String action = actionAndSelector[0].trim().toLowerCase();
                if ("download".equals(action)) break;
                prefixSteps.add(actionAndSelector);
            }

            if (prefixSteps.isEmpty()) return false;

            log.info("[Dynamic:Prefix] Replaying {} common steps from sibling workflow (type={})",
                    prefixSteps.size(), siblingWorkflow.getDocumentType());

            String navUrl = siblingWorkflow.getLoginUrl() != null ? siblingWorkflow.getLoginUrl() : startUrl;
            page.navigate(navUrl);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            waitSafe(page, 2000);
            saveScreenshot(page, debugDir, "prefix_00_start");
            PortalAdapter.StepRecorder.record("navigate", navUrl, null, "prefix replay — reused from sibling workflow");

            int completedSteps = 0;
            boolean hasNavigated = true;

            for (int i = 0; i < prefixSteps.size(); i++) {
                String action = prefixSteps.get(i)[0].trim().toLowerCase();
                String selector = prefixSteps.get(i)[1].trim();
                log.info("[Dynamic:Prefix] Step {}/{}: {} -> {}", i + 1, prefixSteps.size(), action, selector);

                switch (action) {
                    case "click" -> {
                        try {
                            page.click(selector, new Page.ClickOptions().setTimeout(8000));
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 2000);
                            PortalAdapter.StepRecorder.record("click", page.url(), selector, "prefix replay");
                            completedSteps++;
                        } catch (Exception e) {
                            log.warn("[Dynamic:Prefix] Click failed at step {} (non-fatal, LLM will handle): {}",
                                    i + 1, e.getMessage());
                            // Don't fail — form is likely filled; LLM can figure out the click
                        }
                    }
                    case "fill" -> {
                        String fillSelector = selector;
                        String valueTemplate;
                        if (selector.contains("==")) {
                            String[] parts = selector.split("==", 2);
                            fillSelector = parts[0].trim();
                            valueTemplate = parts[1].trim();
                        } else {
                            valueTemplate = guessFillValueType(fillSelector);
                        }
                        String value = resolveValue(valueTemplate, username, password, reference);
                        log.info("[Dynamic:Prefix] fill {} with template {}", fillSelector, valueTemplate);
                        try {
                            page.fill(fillSelector, value);
                            waitSafe(page, 500);
                            PortalAdapter.StepRecorder.record("fill", page.url(), fillSelector, "val=" + valueTemplate);
                            completedSteps++;
                        } catch (Exception e) {
                            log.warn("[Dynamic:Prefix] Fill failed at step {}: {}", i + 1, e.getMessage());
                        }
                    }
                    case "navigate" -> {
                        try {
                            page.navigate(selector);
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                            waitSafe(page, 1500);
                            PortalAdapter.StepRecorder.record("navigate", selector, null, "prefix replay");
                            completedSteps++;
                        } catch (Exception e) {
                            log.warn("[Dynamic:Prefix] Navigate failed at step {}: {}", i + 1, e.getMessage());
                            hasNavigated = false;
                        }
                    }
                    default -> log.debug("[Dynamic:Prefix] Skipping unknown action: {}", action);
                }
                saveScreenshot(page, debugDir, "prefix_%02d".formatted(i + 1));
            }

            // Consider prefix useful if we at least navigated and completed some steps
            boolean useful = hasNavigated && completedSteps > 0;
            log.info("[Dynamic:Prefix] Completed {}/{} steps (useful={}). Page URL: {}. LLM takes over.",
                    completedSteps, prefixSteps.size(), useful, page.url());
            return useful;

        } catch (Exception e) {
            log.warn("[Dynamic:Prefix] Prefix replay failed entirely: {}", e.getMessage());
            return false;
        }
    }

    private void saveScreenshot(Page page, String debugDir, String name) {
        try {
            Path path = Paths.get(debugDir, name + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(false));
            log.debug("[Dynamic] Screenshot: {}", path);
        } catch (Exception e) {
            log.debug("[Dynamic] Screenshot failed: {}", e.getMessage());
        }
    }

    private void waitSafe(Page page, int ms) {
        try { page.waitForTimeout(ms); } catch (Exception ignored) {}
    }

    private String maskValue(String value) {
        if (value == null) return "null";
        if (value.length() <= 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
