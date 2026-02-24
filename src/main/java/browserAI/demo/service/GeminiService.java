package browserAI.demo.service;

import browserAI.demo.exception.GeminiException;
import browserAI.demo.model.dto.GeminiIntent;
import browserAI.demo.util.LogMaskingUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TokenTrackingService tokenTrackingService;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Value("${gemini.temperature}")
    private double temperature;

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

    private static final String SYSTEM_PROMPT = """
            You are an intent extraction engine for a document download agent.
            Given a user's natural language request (in any language including Hindi/Hinglish),
            extract the following fields and return ONLY a valid JSON object:

            {
              "intentType": "<download|query|batch|schedule>",
              "portal": "<the website/portal/service name in lowercase, single word>",
              "documentType": "<the type of document/file in lowercase, single word>",
              "reference": "<document reference/ID/PNR/order number/Aadhaar/PAN/account number, or null>",
              "references": ["<array of multiple references if user mentions more than one, or null>"],
              "email": "<email address if mentioned, or null>",
              "lastName": "<last name/surname if mentioned, or null>",
              "schedule": "<schedule description if user wants recurring download, or null>",
              "query": "<the question about a document if user is asking about data, or null>"
            }

            INTENT TYPE RULES:
            - "download": Download ONE document (default if unclear)
            - "batch": Download MULTIPLE documents (multiple references in one request)
              * Put ALL references in "references" array AND first one in "reference"
            - "query": User is ASKING about data in a previously downloaded document
              * Put the question in "query" field
            - "schedule": User wants RECURRING/PERIODIC downloads
              * Put the schedule description in "schedule" field
            - When user asks for "check-in", "web check-in", or "boarding pass":
              * Set intentType to "download", portal to the airline name, documentType to "boardingpass"
              * The agent will navigate the airline's check-in page dynamically

            PORTAL RULES:
            - Extract the portal/website name from context (airline, bank, government, utility, ecommerce, etc.)
            - Use lowercase single word: e.g. indigo, amazon, sbi, hdfc, uidai, incometax, irctc, etc.
            - For image/wallpaper requests: use "unsplash" as default portal, set documentType to "wallpaper"
            - For government IDs: use the government portal name (e.g. "digilocker", "uidai", "incometax")

            GENERAL RULES:
            - portal and documentType are required, must be lowercase single words
            - reference: any identifier — PNR, order ID, Aadhaar number, PAN, account number, policy number, etc.
            - email: extract any email address. null if not found
            - lastName: extract last name/surname. null if not found
            - Return ONLY the JSON object, no markdown, no explanation
            """;

    public GeminiService(ObjectMapper objectMapper, TokenTrackingService tokenTrackingService) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.tokenTrackingService = tokenTrackingService;
    }

    @Retry(name = "gemini", fallbackMethod = "extractIntentFallback")
    @CircuitBreaker(name = "gemini", fallbackMethod = "extractIntentFallback")
    public GeminiIntent extractIntent(String userInput) {
        log.info("Extracting intent from: {}", LogMaskingUtil.mask(userInput));

        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

            Map<String, Object> requestBody = buildRequestBody(userInput);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new GeminiException("Gemini API returned non-success status: " + response.getStatusCode());
            }

            return parseResponse(response.getBody());

        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to extract intent from Gemini", e);
            throw new GeminiException("Failed to communicate with Gemini API: " + e.getMessage());
        }
    }

    public GeminiIntent extractIntentFallback(String userInput, Throwable t) {
        log.error("Gemini fallback triggered: {}", t.getMessage());
        throw new GeminiException("Gemini service is currently unavailable after retries. Cause: " + t.getMessage());
    }

    /**
     * Dedicated Gemini call for extracting structured data from PDF text.
     * Separate from intent extraction — used by DocumentIntelligenceService.
     */
    public String analyzeDocumentText(String pdfText, String documentType, String portal) {
        log.info("Analyzing document text via Gemini (type={}, portal={}, textLen={})", documentType, portal, pdfText.length());

        String analysisPrompt = """
                You are a document data extraction engine. Given the raw text extracted from a %s document from %s,
                extract ALL structured data and return ONLY a valid JSON object.

                Extract these fields (use null if not found):
                {
                  "invoiceNumber": "<invoice/receipt/document number>",
                  "invoiceDate": "<date of the document>",
                  "vendorName": "<company/vendor name>",
                  "totalAmount": "<total amount with currency symbol>",
                  "taxAmount": "<tax/GST amount>",
                  "currency": "<currency code like INR, USD>",
                  "customerName": "<customer/buyer name>",
                  "pnr": "<PNR/booking reference if applicable>",
                  "flightDetails": "<flight number, route, date if applicable>",
                  "gstin": "<GSTIN number if present>",
                  "items": [{"description": "<item>", "amount": "<amount>"}],
                  "paymentMethod": "<payment method if mentioned>",
                  "additionalFields": {"<any other relevant key>": "<value>"}
                }

                RULES:
                - Return ONLY JSON, no markdown, no explanation
                - Extract ALL amounts, dates, and identifiers you can find
                - For amounts, include the currency symbol (e.g. ₹4,523.00)
                - If a field is not applicable or not found, set it to null
                """.formatted(documentType, portal);

        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

            String truncatedText = pdfText.length() > 8000 ? pdfText.substring(0, 8000) : pdfText;

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("role", "user", "parts", List.of(
                        Map.of("text", analysisPrompt + "\n\nDocument text:\n" + truncatedText)
                    ))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "responseMimeType", "application/json"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new GeminiException("Gemini document analysis failed: " + response.getStatusCode());
            }

            JsonNode root = objectMapper.readTree(response.getBody());

            JsonNode usageMetadata = root.path("usageMetadata");
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            log.info("Document analysis tokens — input: {}, output: {}", inputTokens, outputTokens);

            String sid = currentSessionId.get();
            String uid = currentUserId.get();
            if (sid != null && uid != null) {
                tokenTrackingService.recordUsage(sid, uid, inputTokens, outputTokens);
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) return "{}";

            String jsonText = candidates.get(0).path("content").path("parts").get(0).path("text").asText().trim();
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            return jsonText;

        } catch (Exception e) {
            log.error("Document analysis via Gemini failed: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Gemini call to convert natural language schedule to cron expression.
     */
    public String scheduleToCron(String scheduleDescription) {
        log.info("Converting schedule to cron: {}", scheduleDescription);

        String cronPrompt = """
                Convert this natural language schedule to a Spring cron expression.
                Return ONLY a JSON object: {"cron": "<expression>", "description": "<human readable>"}

                Spring cron format: second minute hour day-of-month month day-of-week
                Examples:
                - "every month on 5th" → "0 0 9 5 * *" (9 AM on 5th of every month)
                - "every day at 8am" → "0 0 8 * * *"
                - "every monday" → "0 0 9 * * MON"
                - "every week on friday" → "0 0 9 * * FRI"
                - "twice a month on 1st and 15th" → "0 0 9 1,15 * *"

                Schedule: "%s"
                """.formatted(scheduleDescription);

        try {
            String url = "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);

            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("role", "user", "parts", List.of(Map.of("text", cronPrompt)))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.1,
                    "responseMimeType", "application/json"
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return null;

            JsonNode root = objectMapper.readTree(response.getBody());

            JsonNode usageMetadata = root.path("usageMetadata");
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            String sid = currentSessionId.get();
            String uid = currentUserId.get();
            if (sid != null && uid != null) {
                tokenTrackingService.recordUsage(sid, uid, inputTokens, outputTokens);
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) return null;

            return candidates.get(0).path("content").path("parts").get(0).path("text").asText().trim();

        } catch (Exception e) {
            log.error("Schedule to cron conversion failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String userInput) {
        return Map.of(
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(
                    Map.of("text", SYSTEM_PROMPT + "\n\nUser request: " + userInput)
                ))
            ),
            "generationConfig", Map.of(
                "temperature", temperature,
                "responseMimeType", "application/json"
            )
        );
    }

    private GeminiIntent parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode usageMetadata = root.path("usageMetadata");
            int inputTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int outputTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            int totalTokens = usageMetadata.path("totalTokenCount").asInt(0);
            log.info("Gemini tokens — input: {}, output: {}, total: {}", inputTokens, outputTokens, totalTokens);

            String sid = currentSessionId.get();
            String uid = currentUserId.get();
            if (sid != null && uid != null) {
                tokenTrackingService.recordUsage(sid, uid, inputTokens, outputTokens);
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) {
                throw new GeminiException("Gemini returned no candidates");
            }

            JsonNode content = candidates.get(0).path("content").path("parts").get(0).path("text");
            String jsonText = content.asText().trim();

            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            GeminiIntent intent = objectMapper.readValue(jsonText, GeminiIntent.class);

            if (!intent.isValid()) {
                throw new GeminiException("Gemini returned invalid intent: " + intent);
            }

            log.info("Extracted intent: {}", intent);
            return intent;

        } catch (GeminiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Gemini response", e);
            throw new GeminiException("Failed to parse Gemini response: " + e.getMessage());
        }
    }
}
