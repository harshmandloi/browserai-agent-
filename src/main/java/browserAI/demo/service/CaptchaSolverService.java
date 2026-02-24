package browserAI.demo.service;

import browserAI.demo.captcha.CaptchaDetector;
import browserAI.demo.captcha.CaptchaResult;
import browserAI.demo.captcha.CaptchaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAPTCHA Solver Service — the AI brain for beating CAPTCHAs.
 *
 * Three-tier strategy:
 *   Tier 1: AI Auto-Solve (Gemini Vision multimodal API)
 *           - Text CAPTCHAs → screenshot → Gemini reads distorted text
 *           - Image selection → screenshot grid → Gemini identifies correct tiles
 *           - Math CAPTCHAs → extract expression → solve
 *           - reCAPTCHA checkbox → auto-click (often works with Playwright)
 *           - Slider → Playwright drag simulation
 *
 *   Tier 2: Retry with fresh CAPTCHA (many portals generate new CAPTCHA on refresh)
 *           - Up to configurable max retries
 *
 *   Tier 3: Manual Fallback (via Redis, like OTP system)
 *           - CAPTCHA image stored in Redis as base64
 *           - User retrieves image via GET /api/captcha/{userId}
 *           - User submits answer via POST /api/captcha
 *           - Service polls Redis for answer
 *
 * Gemini Vision API: Uses multimodal content (image + text prompt) via v1beta endpoint.
 */
@Service
public class CaptchaSolverService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaSolverService.class);

    private static final String CAPTCHA_ANSWER_PREFIX = "captcha:answer:";
    private static final String CAPTCHA_IMAGE_PREFIX = "captcha:image:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(3);
    private static final int POLL_INTERVAL_MS = 2000;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${captcha.max-ai-retries:3}")
    private int maxAiRetries;

    @Value("${captcha.manual-timeout-seconds:120}")
    private int manualTimeoutSeconds;

    public CaptchaSolverService(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.redisTemplate = redisTemplate;
    }

    /**
     * Main entry point — detects and solves any CAPTCHA on the current page.
     *
     * @param page   the Playwright page with the CAPTCHA
     * @param userId the user ID (for manual fallback via Redis)
     * @return CaptchaResult with solve status
     */
    public CaptchaResult detectAndSolve(Page page, String userId) {
        CaptchaType type = CaptchaDetector.detect(page);
        if (type == null) {
            return CaptchaResult.noCaptcha();
        }

        log.info("[CaptchaSolver] CAPTCHA detected: type={}, userId={}", type, userId);

        return switch (type) {
            case TEXT_ALPHANUMERIC -> solveTextCaptcha(page, userId);
            case IMAGE_SELECTION -> solveImageSelectionCaptcha(page, userId);
            case MATH -> solveMathCaptcha(page);
            case SLIDER -> solveSliderCaptcha(page);
            case RECAPTCHA_V2 -> solveRecaptchaCheckbox(page, userId);
            case RECAPTCHA_V3 -> solveRecaptchaV3(page);
            case HCAPTCHA -> solveHCaptchaCheckbox(page, userId);
            case UNKNOWN -> {
                log.warn("[CaptchaSolver] Unknown CAPTCHA type — falling back to manual");
                yield requestManualSolve(page, userId, CaptchaType.UNKNOWN);
            }
        };
    }

    // ==================== TEXT/ALPHANUMERIC CAPTCHA ====================

    /**
     * Solves text-based CAPTCHA using Gemini Vision.
     * Takes a screenshot of the CAPTCHA image, sends to Gemini multimodal API.
     * Retries with fresh CAPTCHA if wrong.
     */
    private CaptchaResult solveTextCaptcha(Page page, String userId) {
        log.info("[CaptchaSolver] Attempting text CAPTCHA solve via Gemini Vision");

        for (int attempt = 1; attempt <= maxAiRetries; attempt++) {
            try {
                byte[] captchaScreenshot = screenshotCaptchaElement(page);
                if (captchaScreenshot == null) {
                    captchaScreenshot = page.screenshot(new Page.ScreenshotOptions()
                            .setFullPage(false));
                }

                String base64Image = Base64.getEncoder().encodeToString(captchaScreenshot);
                String answer = askGeminiVision(base64Image, TEXT_CAPTCHA_PROMPT);

                if (answer != null && !answer.isBlank()) {
                    answer = cleanTextAnswer(answer);
                    log.info("[CaptchaSolver] Gemini Vision answer (attempt {}): '{}'", attempt, answer);

                    fillCaptchaAnswer(page, answer);
                    submitCaptchaForm(page);
                    page.waitForLoadState(LoadState.NETWORKIDLE);

                    if (!CaptchaDetector.isCaptchaPresent(page)) {
                        return CaptchaResult.success(CaptchaType.TEXT_ALPHANUMERIC, answer, attempt);
                    }

                    log.warn("[CaptchaSolver] CAPTCHA still present after attempt {} — retrying", attempt);
                    refreshCaptchaIfPossible(page);
                }
            } catch (Exception e) {
                log.warn("[CaptchaSolver] Text CAPTCHA attempt {} failed: {}", attempt, e.getMessage());
            }
        }

        return requestManualSolve(page, userId, CaptchaType.TEXT_ALPHANUMERIC);
    }

    // ==================== IMAGE SELECTION CAPTCHA ====================

    /**
     * Solves image selection CAPTCHAs (like "select all traffic lights").
     * Screenshots the full challenge, sends to Gemini Vision to identify tiles.
     */
    private CaptchaResult solveImageSelectionCaptcha(Page page, String userId) {
        log.info("[CaptchaSolver] Attempting image selection CAPTCHA via Gemini Vision");

        for (int attempt = 1; attempt <= maxAiRetries; attempt++) {
            try {
                byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
                String base64Image = Base64.getEncoder().encodeToString(screenshot);

                String tileInfo = askGeminiVision(base64Image, IMAGE_SELECTION_PROMPT);
                if (tileInfo != null && !tileInfo.isBlank()) {
                    log.info("[CaptchaSolver] Gemini identified tiles (attempt {}): {}", attempt, tileInfo);

                    List<int[]> tilesToClick = parseTilePositions(tileInfo);
                    clickImageTiles(page, tilesToClick);

                    try {
                        page.click("button:has-text('Verify'), button:has-text('Submit'), " +
                                "button:has-text('Next'), button#recaptcha-verify-button",
                                new Page.ClickOptions().setTimeout(5000));
                    } catch (Exception ignored) {}

                    page.waitForLoadState(LoadState.NETWORKIDLE);

                    if (!CaptchaDetector.isCaptchaPresent(page)) {
                        return CaptchaResult.success(CaptchaType.IMAGE_SELECTION, tileInfo, attempt);
                    }
                }
            } catch (Exception e) {
                log.warn("[CaptchaSolver] Image selection attempt {} failed: {}", attempt, e.getMessage());
            }
        }

        return requestManualSolve(page, userId, CaptchaType.IMAGE_SELECTION);
    }

    // ==================== MATH CAPTCHA ====================

    /**
     * Solves math-based CAPTCHAs by extracting the expression and computing the answer.
     */
    private CaptchaResult solveMathCaptcha(Page page) {
        log.info("[CaptchaSolver] Attempting math CAPTCHA solve");

        try {
            String mathText = extractMathExpression(page);
            if (mathText != null) {
                String answer = solveMathExpression(mathText);
                if (answer != null) {
                    log.info("[CaptchaSolver] Math: '{}' = '{}'", mathText, answer);
                    fillCaptchaAnswer(page, answer);
                    submitCaptchaForm(page);
                    page.waitForLoadState(LoadState.NETWORKIDLE);

                    if (!CaptchaDetector.isCaptchaPresent(page)) {
                        return CaptchaResult.success(CaptchaType.MATH, answer, 1);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[CaptchaSolver] Math CAPTCHA failed: {}", e.getMessage());
        }

        return CaptchaResult.failed(CaptchaType.MATH, "Could not solve math CAPTCHA");
    }

    // ==================== SLIDER CAPTCHA ====================

    /**
     * Solves slider CAPTCHAs by dragging the slider to the end position.
     */
    private CaptchaResult solveSliderCaptcha(Page page) {
        log.info("[CaptchaSolver] Attempting slider CAPTCHA solve");

        String[] sliderSelectors = {
                "[class*='slider'] button",
                "[class*='slider'] .slider-btn",
                "[class*='slider'] [draggable]",
                ".geetest_slider_button",
                "[class*='captcha-slider'] span"
        };

        for (String selector : sliderSelectors) {
            try {
                Locator slider = page.locator(selector);
                if (slider.count() > 0 && slider.first().isVisible()) {
                    var box = slider.first().boundingBox();
                    if (box != null) {
                        double startX = box.x + box.width / 2;
                        double startY = box.y + box.height / 2;
                        double endX = startX + 280;

                        page.mouse().move(startX, startY);
                        page.mouse().down();

                        for (int i = 0; i <= 20; i++) {
                            double currentX = startX + (endX - startX) * i / 20.0;
                            double jitter = (Math.random() - 0.5) * 2;
                            page.mouse().move(currentX, startY + jitter);
                            Thread.sleep(15 + (int) (Math.random() * 30));
                        }

                        page.mouse().up();
                        page.waitForLoadState(LoadState.NETWORKIDLE);

                        if (!CaptchaDetector.isCaptchaPresent(page)) {
                            return CaptchaResult.success(CaptchaType.SLIDER, "dragged", 1);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[CaptchaSolver] Slider attempt failed for {}: {}", selector, e.getMessage());
            }
        }

        return CaptchaResult.failed(CaptchaType.SLIDER, "Slider CAPTCHA could not be solved");
    }

    // ==================== RECAPTCHA v2 ====================

    /**
     * Attempts to solve reCAPTCHA v2 by clicking the checkbox.
     * If image challenge appears, falls back to Gemini Vision or manual.
     */
    /**
     * Handles reCAPTCHA v3 (invisible/score-based).
     * v3 tokens are generated automatically by the page's JS — we just need to wait
     * for the token to be populated in the hidden input field, or trigger it via JS.
     */
    private CaptchaResult solveRecaptchaV3(Page page) {
        log.info("[CaptchaSolver] Handling reCAPTCHA v3 (invisible) — triggering token generation");
        try {
            // reCAPTCHA v3 tokens are generated via grecaptcha.execute()
            // Try to trigger it and wait for the hidden token field to populate
            Object tokenResult = page.evaluate("""
                (async () => {
                    if (typeof grecaptcha !== 'undefined' && typeof grecaptcha.execute === 'function') {
                        try {
                            // Find the site key from the script or data attribute
                            let siteKey = null;
                            const scripts = document.querySelectorAll('script[src*="recaptcha"]');
                            for (const s of scripts) {
                                const match = s.src.match(/render=([^&]+)/);
                                if (match && match[1] !== 'explicit') { siteKey = match[1]; break; }
                            }
                            if (!siteKey) {
                                const el = document.querySelector('[data-sitekey]');
                                if (el) siteKey = el.getAttribute('data-sitekey');
                            }
                            if (siteKey) {
                                const token = await grecaptcha.execute(siteKey, {action: 'submit'});
                                // Try to set the token in the hidden input
                                const input = document.querySelector('input[name="g-recaptcha-response"], #g-recaptcha-response, textarea[name="g-recaptcha-response"]');
                                if (input) input.value = token;
                                return 'token_set';
                            }
                            return 'no_sitekey';
                        } catch (e) { return 'error: ' + e.message; }
                    }
                    return 'no_grecaptcha';
                })()
            """);

            String result = String.valueOf(tokenResult);
            log.info("[CaptchaSolver] reCAPTCHA v3 result: {}", result);

            if ("token_set".equals(result)) {
                return CaptchaResult.success(CaptchaType.RECAPTCHA_V3, "token_generated", 1);
            }

            // v3 sometimes doesn't need explicit solving — just proceed
            log.info("[CaptchaSolver] reCAPTCHA v3 may auto-pass for high-score users — proceeding");
            return CaptchaResult.success(CaptchaType.RECAPTCHA_V3, "auto_pass", 1);

        } catch (Exception e) {
            log.warn("[CaptchaSolver] reCAPTCHA v3 handling failed: {}", e.getMessage());
            return CaptchaResult.success(CaptchaType.RECAPTCHA_V3, "proceed_despite_error", 1);
        }
    }

    private CaptchaResult solveRecaptchaCheckbox(Page page, String userId) {
        log.info("[CaptchaSolver] Attempting reCAPTCHA v2 checkbox click");

        try {
            var frames = page.frames();
            for (var frame : frames) {
                if (frame.url().contains("recaptcha")) {
                    try {
                        Locator checkbox = frame.locator("#recaptcha-anchor, .recaptcha-checkbox");
                        if (checkbox.count() > 0) {
                            checkbox.first().click();
                            Thread.sleep(2000);

                            if (isRecaptchaSolved(page)) {
                                return CaptchaResult.success(CaptchaType.RECAPTCHA_V2, "checkbox_click", 1);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (CaptchaDetector.isCaptchaPresent(page)) {
                log.info("[CaptchaSolver] reCAPTCHA checkbox alone didn't work — image challenge may have appeared");
                return solveImageSelectionCaptcha(page, userId);
            }

            return CaptchaResult.success(CaptchaType.RECAPTCHA_V2, "checkbox_click", 1);

        } catch (Exception e) {
            log.warn("[CaptchaSolver] reCAPTCHA failed: {}", e.getMessage());
            return requestManualSolve(page, userId, CaptchaType.RECAPTCHA_V2);
        }
    }

    // ==================== HCAPTCHA ====================

    private CaptchaResult solveHCaptchaCheckbox(Page page, String userId) {
        log.info("[CaptchaSolver] Attempting hCaptcha checkbox click");

        try {
            var frames = page.frames();
            for (var frame : frames) {
                if (frame.url().contains("hcaptcha")) {
                    try {
                        Locator checkbox = frame.locator("#checkbox, .check");
                        if (checkbox.count() > 0) {
                            checkbox.first().click();
                            Thread.sleep(2000);
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (!CaptchaDetector.isCaptchaPresent(page)) {
                return CaptchaResult.success(CaptchaType.HCAPTCHA, "checkbox_click", 1);
            }
        } catch (Exception e) {
            log.warn("[CaptchaSolver] hCaptcha failed: {}", e.getMessage());
        }

        return requestManualSolve(page, userId, CaptchaType.HCAPTCHA);
    }

    // ==================== MANUAL FALLBACK ====================

    /**
     * When AI can't solve, stores CAPTCHA image in Redis and waits for user to solve.
     * Same pattern as OTP — polls Redis for the user's answer.
     */
    private CaptchaResult requestManualSolve(Page page, String userId, CaptchaType type) {
        log.info("[CaptchaSolver] Falling back to manual solve for user={}", userId);

        try {
            byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            String base64Image = Base64.getEncoder().encodeToString(screenshot);

            String imageKey = CAPTCHA_IMAGE_PREFIX + userId;
            redisTemplate.opsForValue().set(imageKey, base64Image, CAPTCHA_TTL);

            String answer = waitForManualAnswer(userId);

            if (answer != null && !answer.isBlank()) {
                fillCaptchaAnswer(page, answer);
                submitCaptchaForm(page);
                page.waitForLoadState(LoadState.NETWORKIDLE);

                if (!CaptchaDetector.isCaptchaPresent(page)) {
                    return CaptchaResult.success(type, answer, maxAiRetries + 1);
                }
            }
        } catch (Exception e) {
            log.error("[CaptchaSolver] Manual solve failed: {}", e.getMessage());
        }

        return CaptchaResult.manualNeeded(type, maxAiRetries);
    }

    // ==================== USER-FACING METHODS (for controller) ====================

    /**
     * Submit CAPTCHA answer for a pending browser session.
     */
    public void submitCaptchaAnswer(String userId, String answer) {
        String key = CAPTCHA_ANSWER_PREFIX + userId;
        redisTemplate.opsForValue().set(key, answer, CAPTCHA_TTL);
        log.info("[CaptchaSolver] Manual CAPTCHA answer submitted for user: {}", userId);
    }

    /**
     * Get the CAPTCHA image for manual solving (base64 PNG).
     */
    public String getCaptchaImage(String userId) {
        return redisTemplate.opsForValue().get(CAPTCHA_IMAGE_PREFIX + userId);
    }

    /**
     * Check if there's a pending CAPTCHA for this user.
     */
    public boolean isCaptchaPending(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(CAPTCHA_IMAGE_PREFIX + userId));
    }

    // ==================== GEMINI VISION API ====================

    /**
     * Sends an image to Gemini's multimodal API for analysis.
     * Uses inline base64 image data with a text prompt.
     */
    private String askGeminiVision(String base64Image, String prompt) {
        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> imagePart = Map.of(
                    "inlineData", Map.of(
                            "mimeType", "image/png",
                            "data", base64Image
                    )
            );

            Map<String, Object> body = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(textPart, imagePart))
                    ),
                    "generationConfig", Map.of("temperature", 0.0)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                return root.path("candidates").get(0).path("content")
                        .path("parts").get(0).path("text").asText().trim();
            }
        } catch (Exception e) {
            log.warn("[CaptchaSolver] Gemini Vision call failed: {}", e.getMessage());
        }
        return null;
    }

    // ==================== HELPER METHODS ====================

    private byte[] screenshotCaptchaElement(Page page) {
        Locator captchaImage = CaptchaDetector.findCaptchaImageElement(page);
        if (captchaImage != null) {
            try {
                return captchaImage.screenshot();
            } catch (Exception e) {
                log.warn("[CaptchaSolver] Element screenshot failed, will use full page");
            }
        }
        return null;
    }

    private void fillCaptchaAnswer(Page page, String answer) {
        Locator inputField = CaptchaDetector.findCaptchaInputField(page);
        if (inputField != null) {
            inputField.fill(answer);
            return;
        }

        String[] fallbackSelectors = {
                "input[name*='captcha' i]",
                "input[id*='captcha' i]",
                "input[placeholder*='code' i]",
                "input[placeholder*='enter' i]",
                "input[type='text'][maxlength]"
        };

        for (String sel : fallbackSelectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    loc.first().fill(answer);
                    return;
                }
            } catch (Exception ignored) {}
        }

        log.warn("[CaptchaSolver] Could not find CAPTCHA input field to fill answer");
    }

    private void submitCaptchaForm(Page page) {
        String[] submitSelectors = {
                "button[type='submit']",
                "input[type='submit']",
                "button:has-text('Submit')",
                "button:has-text('Verify')",
                "button:has-text('Continue')",
                "button:has-text('Login')",
                "button:has-text('Sign in')"
        };

        for (String sel : submitSelectors) {
            try {
                Locator btn = page.locator(sel);
                if (btn.count() > 0 && btn.first().isVisible()) {
                    btn.first().click();
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private void refreshCaptchaIfPossible(Page page) {
        String[] refreshSelectors = {
                "a:has-text('refresh')",
                "a:has-text('Refresh')",
                "button:has-text('refresh')",
                "[class*='captcha'] a[href*='refresh']",
                "[class*='captcha'] img[onclick]",
                "[title*='refresh' i]",
                "[alt*='refresh' i]",
                ".captcha-refresh",
                "#refreshCaptcha"
        };

        for (String sel : refreshSelectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    loc.first().click();
                    Thread.sleep(1000);
                    log.info("[CaptchaSolver] CAPTCHA refreshed via: {}", sel);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private String cleanTextAnswer(String raw) {
        return raw.replaceAll("[\\n\\r\"'`]", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private String extractMathExpression(Page page) {
        String[] selectors = {
                "label:has-text('+')",
                "span:has-text('+')",
                "[class*='captcha']:has-text('+')",
                "label:has-text('=')",
                "[class*='math']"
        };

        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() > 0) {
                    String text = loc.first().innerText().trim();
                    if (text.matches(".*\\d+.*[+\\-×x*].*\\d+.*")) {
                        return text;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String solveMathExpression(String expression) {
        try {
            String cleaned = expression.replaceAll("[^0-9+\\-*xX×=/]", "");
            cleaned = cleaned.replace("×", "*").replace("x", "*").replace("X", "*");
            cleaned = cleaned.replace("=", "");

            Pattern pattern = Pattern.compile("(\\d+)\\s*([+\\-*/])\\s*(\\d+)");
            Matcher matcher = pattern.matcher(cleaned);

            if (matcher.find()) {
                int a = Integer.parseInt(matcher.group(1));
                String op = matcher.group(2);
                int b = Integer.parseInt(matcher.group(3));

                int result = switch (op) {
                    case "+" -> a + b;
                    case "-" -> a - b;
                    case "*" -> a * b;
                    case "/" -> b != 0 ? a / b : 0;
                    default -> throw new IllegalArgumentException("Unknown operator: " + op);
                };

                return String.valueOf(result);
            }
        } catch (Exception e) {
            log.warn("[CaptchaSolver] Math expression parsing failed: {}", e.getMessage());
        }
        return null;
    }

    private boolean isRecaptchaSolved(Page page) {
        try {
            for (var frame : page.frames()) {
                if (frame.url().contains("recaptcha")) {
                    Locator check = frame.locator(".recaptcha-checkbox-checked, [aria-checked='true']");
                    if (check.count() > 0) return true;
                }
            }
        } catch (Exception ignored) {}

        try {
            Locator token = page.locator("textarea[name='g-recaptcha-response']");
            if (token.count() > 0) {
                String val = token.first().inputValue();
                return val != null && !val.isBlank();
            }
        } catch (Exception ignored) {}

        return false;
    }

    private List<int[]> parseTilePositions(String geminiResponse) {
        List<int[]> positions = new ArrayList<>();

        Pattern rowCol = Pattern.compile("(?:row\\s*(\\d+).*?col(?:umn)?\\s*(\\d+))|(?:(\\d+)\\s*[,x]\\s*(\\d+))");
        Matcher matcher = rowCol.matcher(geminiResponse.toLowerCase());

        while (matcher.find()) {
            int row, col;
            if (matcher.group(1) != null) {
                row = Integer.parseInt(matcher.group(1));
                col = Integer.parseInt(matcher.group(2));
            } else {
                row = Integer.parseInt(matcher.group(3));
                col = Integer.parseInt(matcher.group(4));
            }
            positions.add(new int[]{row, col});
        }

        if (positions.isEmpty()) {
            Pattern tileNum = Pattern.compile("(?:tile|image|square|cell)\\s*#?\\s*(\\d+)");
            Matcher numMatcher = tileNum.matcher(geminiResponse.toLowerCase());
            while (numMatcher.find()) {
                int num = Integer.parseInt(numMatcher.group(1));
                int row = (num - 1) / 3 + 1;
                int col = (num - 1) % 3 + 1;
                positions.add(new int[]{row, col});
            }
        }

        return positions;
    }

    private void clickImageTiles(Page page, List<int[]> tiles) {
        try {
            Locator grid = page.locator(".rc-imageselect-table, [class*='captcha-grid'], " +
                    "[class*='image-select'] table");
            if (grid.count() > 0) {
                var box = grid.first().boundingBox();
                if (box != null) {
                    int gridSize = 3;
                    double cellW = box.width / gridSize;
                    double cellH = box.height / gridSize;

                    for (int[] tile : tiles) {
                        int row = tile[0] - 1;
                        int col = tile[1] - 1;
                        double clickX = box.x + col * cellW + cellW / 2;
                        double clickY = box.y + row * cellH + cellH / 2;
                        page.mouse().click(clickX, clickY);
                        Thread.sleep(300);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[CaptchaSolver] Tile clicking failed: {}", e.getMessage());
        }
    }

    private String waitForManualAnswer(String userId) {
        String key = CAPTCHA_ANSWER_PREFIX + userId;
        long deadline = System.currentTimeMillis() + (manualTimeoutSeconds * 1000L);

        log.info("[CaptchaSolver] Waiting for manual CAPTCHA answer for user: {} (timeout: {}s)",
                userId, manualTimeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            String answer = redisTemplate.opsForValue().get(key);
            if (answer != null && !answer.isBlank()) {
                redisTemplate.delete(key);
                redisTemplate.delete(CAPTCHA_IMAGE_PREFIX + userId);
                log.info("[CaptchaSolver] Manual answer received for user: {}", userId);
                return answer;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        log.warn("[CaptchaSolver] Manual CAPTCHA answer timeout for user: {}", userId);
        return null;
    }

    // ==================== PROMPTS ====================

    private static final String TEXT_CAPTCHA_PROMPT = """
            You are looking at a CAPTCHA image that contains distorted text or numbers.
            Your task is to read and transcribe the exact characters shown in the CAPTCHA.
            
            Rules:
            - Return ONLY the characters/numbers you see, nothing else
            - No spaces unless there are clearly separate groups
            - No explanation, no markdown, no quotes
            - Be case-sensitive (if you see uppercase, return uppercase)
            - Common confusions: 0 vs O, 1 vs l vs I, 5 vs S, 8 vs B
            - If unsure about a character, make your best guess
            
            Return ONLY the CAPTCHA text:""";

    private static final String IMAGE_SELECTION_PROMPT = """
            You are looking at a CAPTCHA image selection challenge.
            There is a 3x3 grid of images (9 tiles total).
            The challenge asks you to select specific tiles containing a particular object.
            
            Analyze the challenge text at the top to understand what to select.
            Then identify which tiles in the 3x3 grid contain the requested object.
            
            Tiles are numbered 1-9 (left to right, top to bottom):
              [1] [2] [3]
              [4] [5] [6]
              [7] [8] [9]
            
            Return the tile numbers that match, in this format:
            tile 1, tile 4, tile 7
            
            Be precise. Only include tiles that clearly contain the requested object.
            Return ONLY the tile numbers:""";
}
