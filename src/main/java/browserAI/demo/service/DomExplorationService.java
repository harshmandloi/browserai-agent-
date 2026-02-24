package browserAI.demo.service;

import browserAI.demo.exception.GeminiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DOM Exploration Service — enables AI-guided navigation of unknown portals.
 *
 * When a portal's workflow is NOT pre-defined:
 * 1. Extracts clickable elements, links, buttons, forms from the current page DOM
 * 2. Summarizes the DOM structure (not sending raw HTML to LLM — token protection)
 * 3. Sends the summarized DOM to Gemini, asking "what should I click next?"
 * 4. Executes the LLM's recommended action
 * 5. Repeats until document is found or max depth reached
 *
 * This is the "learning" capability of the agent.
 */
@Service
public class DomExplorationService {

    private static final Logger log = LoggerFactory.getLogger(DomExplorationService.class);

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final GovernanceService governanceService;
    private final TokenTrackingService tokenTrackingService;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public void setSessionContext(String sessionId, String userId) {
        currentSessionId.set(sessionId);
        currentUserId.set(userId);
    }

    public void clearSessionContext() {
        currentSessionId.remove();
        currentUserId.remove();
    }

    public DomExplorationService(ObjectMapper objectMapper, GovernanceService governanceService,
                                 TokenTrackingService tokenTrackingService) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.governanceService = governanceService;
        this.tokenTrackingService = tokenTrackingService;
    }

    /**
     * Extracts a summarized DOM structure from the current page.
     * Captures interactive elements with rich metadata to help the LLM
     * understand what each field is for (label, placeholder, aria-label, type).
     */
    public DomSummary extractDomSummary(Page page) {
        DomSummary summary = new DomSummary();
        summary.setUrl(page.url());
        summary.setTitle(page.title());

        // Extract links (prioritize visible, relevant ones)
        List<DomElement> links = new ArrayList<>();
        Locator linkLocator = page.locator("a[href]");
        int linkCount = Math.min(linkLocator.count(), 40);
        for (int i = 0; i < linkCount; i++) {
            try {
                Locator link = linkLocator.nth(i);
                if (link.isVisible()) {
                    String text = truncate(link.innerText(), 80);
                    if (text.isBlank()) continue;
                    DomElement el = new DomElement();
                    el.setType("link");
                    el.setText(text);
                    el.setHref(link.getAttribute("href"));
                    el.setSelector(buildSelector(link, "a", i));
                    links.add(el);
                }
            } catch (Exception ignored) {}
        }
        summary.setLinks(links);

        // Extract buttons
        List<DomElement> buttons = new ArrayList<>();
        Locator buttonLocator = page.locator("button, input[type='submit'], input[type='button'], [role='button']");
        int btnCount = Math.min(buttonLocator.count(), 20);
        for (int i = 0; i < btnCount; i++) {
            try {
                Locator btn = buttonLocator.nth(i);
                if (btn.isVisible()) {
                    DomElement el = new DomElement();
                    el.setType("button");
                    String text = truncate(btn.innerText(), 80);
                    if (text.isBlank()) text = btn.getAttribute("value");
                    if (text == null || text.isBlank()) text = btn.getAttribute("aria-label");
                    el.setText(text != null ? text : "[unnamed button]");
                    el.setSelector(buildSelector(btn, "button", i));
                    buttons.add(el);
                }
            } catch (Exception ignored) {}
        }
        summary.setButtons(buttons);

        // Extract ALL form fields (text, email, tel, password, number, search, textarea, select)
        List<DomElement> inputs = new ArrayList<>();
        Locator inputLocator = page.locator(
                "input[type='text'], input[type='email'], input[type='tel'], " +
                "input[type='password'], input[type='number'], input[type='search'], " +
                "input:not([type]), textarea, select");
        int inputCount = Math.min(inputLocator.count(), 20);
        for (int i = 0; i < inputCount; i++) {
            try {
                Locator inp = inputLocator.nth(i);
                if (inp.isVisible()) {
                    DomElement el = new DomElement();
                    String inputType = inp.getAttribute("type");
                    el.setType(inputType != null ? "input[" + inputType + "]" : "input");

                    // Build rich description: label > placeholder > aria-label > name
                    String label = findLabelFor(page, inp);
                    String placeholder = inp.getAttribute("placeholder");
                    String ariaLabel = inp.getAttribute("aria-label");
                    String name = inp.getAttribute("name");

                    StringBuilder desc = new StringBuilder();
                    if (label != null && !label.isBlank()) desc.append("label: '").append(label).append("' ");
                    if (placeholder != null && !placeholder.isBlank()) desc.append("placeholder: '").append(placeholder).append("' ");
                    if (ariaLabel != null && !ariaLabel.isBlank()) desc.append("aria: '").append(ariaLabel).append("' ");
                    if (name != null && !name.isBlank()) desc.append("name: '").append(name).append("'");
                    el.setText(desc.toString().trim());

                    el.setSelector(buildSelector(inp, "input", i));
                    inputs.add(el);
                }
            } catch (Exception ignored) {}
        }
        summary.setInputs(inputs);

        log.debug("[DOM] Extracted: {} links, {} buttons, {} inputs from {}",
                links.size(), buttons.size(), inputs.size(), page.url());
        return summary;
    }

    /**
     * Builds the most specific CSS selector possible for an element.
     * Priority: #id > [data-testid] > [name] > [placeholder] > nth-of-type
     */
    private String buildSelector(Locator element, String tag, int index) {
        try {
            String id = element.getAttribute("id");
            if (id != null && !id.isBlank()) return "#" + id;

            String testId = element.getAttribute("data-testid");
            if (testId != null && !testId.isBlank()) return "[data-testid='%s']".formatted(testId);

            String name = element.getAttribute("name");
            if (name != null && !name.isBlank()) return "%s[name='%s']".formatted(tag, name);

            String placeholder = element.getAttribute("placeholder");
            if (placeholder != null && !placeholder.isBlank())
                return "%s[placeholder='%s']".formatted(tag, placeholder.replace("'", "\\'"));

            String ariaLabel = element.getAttribute("aria-label");
            if (ariaLabel != null && !ariaLabel.isBlank())
                return "%s[aria-label='%s']".formatted(tag, ariaLabel.replace("'", "\\'"));

            String text = truncate(element.innerText(), 30);
            if (text != null && !text.isBlank() && !"input".equals(tag))
                return "%s:has-text('%s')".formatted(tag, text.replace("'", "\\'"));

        } catch (Exception ignored) {}
        return "%s:nth-of-type(%d)".formatted(tag, index + 1);
    }

    /**
     * Finds the label text for an input field.
     * Checks: associated <label>, parent label, preceding sibling text.
     */
    private String findLabelFor(Page page, Locator input) {
        try {
            String id = input.getAttribute("id");
            if (id != null && !id.isBlank()) {
                Locator label = page.locator("label[for='%s']".formatted(id));
                if (label.count() > 0) return truncate(label.first().innerText(), 50);
            }
            Locator parentLabel = input.locator("xpath=ancestor::label");
            if (parentLabel.count() > 0) return truncate(parentLabel.first().innerText(), 50);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Asks Gemini to decide the next action based on DOM summary.
     * Returns a structured action: click(selector), fill(selector, value), navigate(url), or done.
     */
    public ExplorationAction decideNextAction(DomSummary domSummary, String goal, String memoryContext,
                                              List<String> previousActions, int depth) {
        governanceService.checkExplorationDepth(depth);

        String prompt = buildExplorationPrompt(domSummary, goal, memoryContext, previousActions);

        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

            Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature", 0.1, "responseMimeType", "application/json")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new GeminiException("Gemini DOM exploration failed: " + response.getStatusCode());
            }

            return parseExplorationResponse(response.getBody());

        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            log.error("DOM exploration LLM call failed", e);
            throw new GeminiException("DOM exploration failed: " + e.getMessage());
        }
    }

    private String buildExplorationPrompt(DomSummary domSummary, String goal,
                                          String memoryContext, List<String> previousActions) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a browser automation agent. You are on a web page and need to achieve a goal.
                Analyze the page structure and decide the NEXT SINGLE action to take.

                GOAL: %s

                CURRENT PAGE:
                  URL: %s
                  Title: %s

                """.formatted(goal, domSummary.getUrl(), domSummary.getTitle()));

        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("MEMORY (past experience):\n%s\n\n".formatted(memoryContext));
        }

        if (previousActions != null && !previousActions.isEmpty()) {
            sb.append("ACTIONS TAKEN SO FAR:\n");
            for (int i = 0; i < previousActions.size(); i++) {
                sb.append("  %d. %s\n".formatted(i + 1, previousActions.get(i)));
            }
            sb.append("\n");
        }

        sb.append("AVAILABLE ELEMENTS:\n");

        sb.append("\nLinks:\n");
        for (DomElement link : domSummary.getLinks()) {
            sb.append("  - text: '%s' | href: %s | selector: %s\n".formatted(
                    link.getText(), link.getHref(), link.getSelector()));
        }

        sb.append("\nButtons:\n");
        for (DomElement btn : domSummary.getButtons()) {
            sb.append("  - text: '%s' | selector: %s\n".formatted(btn.getText(), btn.getSelector()));
        }

        sb.append("\nInput fields:\n");
        for (DomElement inp : domSummary.getInputs()) {
            sb.append("  - type: %s | %s | selector: %s\n".formatted(
                    inp.getType(), inp.getText(), inp.getSelector()));
        }

        sb.append("""

                CRITICAL RULE — ALWAYS DOWNLOAD:
                - If you see ANY download icon, "Download" link/button, PDF link, or save option → use action="download"
                - NEVER say "done" while a download button or link is visible on the page
                - Your PRIMARY job is to trigger a file download, not just view content
                - Even if information is displayed on screen, look for a download/save/export button and click it
                - Only use "done" if there is truly NO download option anywhere on the page

                RESPOND with ONLY a JSON object (no markdown, no explanation outside JSON):
                {
                  "action": "click" | "fill" | "navigate" | "download" | "done" | "stuck",
                  "selector": "<css selector to interact with>",
                  "value": "<placeholder like {{reference}} or {{username}} or {{email}} or {{password}} — or literal text>",
                  "url": "<url if action is navigate>",
                  "reasoning": "<one-line explanation>"
                }
                
                For "fill" actions, use these placeholders as value:
                  {{reference}} — for PNR, booking ref, order ID, invoice number fields
                  {{username}}  — for last name, surname, name, username fields
                  {{email}}     — for email address fields
                  {{password}}  — for password fields
                  
                For "download" action: use the selector of the download button/link/icon.
                For "stuck" action: explain what data is missing in reasoning.
                """);

        return sb.toString();
    }

    private ExplorationAction parseExplorationResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode usageMetadata = root.path("usageMetadata");
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            log.info("[DOM] Gemini tokens — input: {}, output: {}, total: {}",
                    inputTokens, outputTokens, inputTokens + outputTokens);

            String sid = currentSessionId.get();
            String uid = currentUserId.get();
            if (sid != null && uid != null) {
                tokenTrackingService.recordUsage(sid, uid, inputTokens, outputTokens);
            }

            JsonNode textNode = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
            String jsonText = textNode.asText().trim();

            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            // Handle LLM returning an array of actions — take the first one
            JsonNode parsed = objectMapper.readTree(jsonText);
            if (parsed.isArray() && parsed.size() > 0) {
                log.info("[DOM] LLM returned {} actions in array, taking first one", parsed.size());
                return objectMapper.treeToValue(parsed.get(0), ExplorationAction.class);
            }

            return objectMapper.readValue(jsonText, ExplorationAction.class);
        } catch (Exception e) {
            log.error("Failed to parse exploration response", e);
            throw new GeminiException("Failed to parse DOM exploration response: " + e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        text = text.strip().replaceAll("\\s+", " ");
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ==================== Inner classes ====================

    public static class DomSummary {
        private String url;
        private String title;
        private List<DomElement> links = new ArrayList<>();
        private List<DomElement> buttons = new ArrayList<>();
        private List<DomElement> inputs = new ArrayList<>();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<DomElement> getLinks() { return links; }
        public void setLinks(List<DomElement> links) { this.links = links; }
        public List<DomElement> getButtons() { return buttons; }
        public void setButtons(List<DomElement> buttons) { this.buttons = buttons; }
        public List<DomElement> getInputs() { return inputs; }
        public void setInputs(List<DomElement> inputs) { this.inputs = inputs; }
    }

    public static class DomElement {
        private String type;
        private String text;
        private String selector;
        private String href;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }
        public String getHref() { return href; }
        public void setHref(String href) { this.href = href; }
    }

    public static class ExplorationAction {
        private String action;
        private String selector;
        private String value;
        private String url;
        private String reasoning;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getSelector() { return selector; }
        public void setSelector(String selector) { this.selector = selector; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    }
}
