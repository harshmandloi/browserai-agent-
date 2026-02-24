package browserAI.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Web search service using SerpAPI to find portal login/document URLs.
 * Results are cached in Redis (24h TTL) to reduce API usage.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final String CACHE_PREFIX = "portal:url:";
    private static final String SERPAPI_BASE = "https://serpapi.com/search.json";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${serpapi.api-key:}")
    private String serpApiKey;

    public WebSearchService(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /** Check if SerpAPI is configured. */
    public boolean isAvailable() {
        return serpApiKey != null && !serpApiKey.isBlank();
    }

    /**
     * Search for the MOST DIRECT portal URL for a document type.
     * Strategy: search for direct document page first (no login assumed),
     * then fallback to login/manage page if no direct path found.
     */
    public String searchPortalUrl(String portalName, String documentType) {
        if (portalName == null || portalName.isBlank()) {
            log.warn("searchPortalUrl called with empty portalName");
            return null;
        }

        // Cache key includes document type — different docs may have different URLs
        String cacheKey = CACHE_PREFIX + portalName.trim().toLowerCase()
                + (documentType != null ? ":" + documentType.trim().toLowerCase() : "");

        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                log.info("Cache hit for portal URL: {} → {}", portalName, cached);
                return cached;
            }
        } catch (Exception e) {
            log.warn("Redis cache read failed for {}: {}", portalName, e.getMessage());
        }

        if (!isAvailable()) {
            log.warn("SerpAPI key not configured; skipping search for {}", portalName);
            return null;
        }

        // Strategy: search for DIRECT document path first (no login assumption)
        String url = null;
        if (documentType != null && !documentType.isBlank()) {
            String docTypeClean = documentType.replace("_", " ");

            // 1st: Direct document download page
            url = performSearch(portalName + " view " + docTypeClean + " download", portalName);

            // 2nd: Document page without "download"
            if (url == null) {
                url = performSearch(portalName + " " + docTypeClean, portalName);
            }

            // 3rd: Manage/retrieve page (still no "login")
            if (url == null) {
                url = performSearch(portalName + " manage booking " + docTypeClean, portalName);
            }
        }

        // 4th: General portal page (last resort)
        if (url == null) {
            url = performSearch(portalName + " portal", portalName);
        }

        if (url != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, url, CACHE_TTL);
                log.info("Cached portal URL for {}:{} → {}", portalName, documentType, url);
            } catch (Exception e) {
                log.warn("Redis cache write failed for {}: {}", portalName, e.getMessage());
            }
        }
        return url;
    }

    /** Search for structured portal info (login URL, document section URL, description). */
    public PortalSearchResult searchPortalInfo(String portalName) {
        if (portalName == null || portalName.isBlank()) {
            log.warn("searchPortalInfo called with empty portalName");
            return null;
        }
        if (!isAvailable()) {
            log.warn("SerpAPI key not configured; skipping search for {}", portalName);
            return null;
        }

        String loginUrl = null;
        String documentPageUrl = null;
        String description = null;

        try {
            String loginQuery = portalName + " login page";
            JsonNode loginResults = searchSerpApi(loginQuery);
            if (loginResults != null) {
                loginUrl = extractBestUrl(loginResults, portalName, true);
                description = extractFirstSnippet(loginResults);
            }
        } catch (Exception e) {
            log.warn("Login search failed for {}: {}", portalName, e.getMessage());
        }

        try {
            String docQuery = portalName + " documents download";
            JsonNode docResults = searchSerpApi(docQuery);
            if (docResults != null) {
                documentPageUrl = extractBestUrl(docResults, portalName, false);
                if (description == null) {
                    description = extractFirstSnippet(docResults);
                }
            }
        } catch (Exception e) {
            log.warn("Document page search failed for {}: {}", portalName, e.getMessage());
        }

        return new PortalSearchResult(portalName, loginUrl, documentPageUrl, description);
    }

    public record PortalSearchResult(String portalName, String loginUrl, String documentPageUrl, String description) {}

    private String performSearch(String query, String portalName) {
        JsonNode results = searchSerpApi(query);
        return results != null ? extractBestUrl(results, portalName, true) : null;
    }

    private JsonNode searchSerpApi(String query) {
        try {
            String url = String.format("%s?q=%s&api_key=%s&engine=google",
                    SERPAPI_BASE,
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLEncoder.encode(serpApiKey, java.nio.charset.StandardCharsets.UTF_8));
            String body = restTemplate.getForObject(URI.create(url), String.class);
            if (body == null || body.isBlank()) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                log.warn("SerpAPI error: {}", error.asText());
                return null;
            }
            return root.path("organic_results");
        } catch (Exception e) {
            log.warn("SerpAPI request failed for query '{}': {}", query, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the best URL from search results.
     * Prioritizes: direct document page > portal official page > any relevant result.
     * Skips social media, help/FAQ pages.
     */
    private String extractBestUrl(JsonNode organicResults, String portalName, boolean preferLogin) {
        if (organicResults == null || !organicResults.isArray() || organicResults.isEmpty()) return null;

        String portalLower = portalName != null ? portalName.trim().toLowerCase() : "";
        String bestDirect = null;
        String bestPortal = null;
        String firstResult = null;

        for (JsonNode item : organicResults) {
            JsonNode link = item.path("link");
            if (link.isMissingNode() || link.isNull()) continue;
            String url = link.asText().trim();
            if (url.isBlank()) continue;

            // Skip noise: google, social media, q&a sites
            String urlLower = url.toLowerCase();
            if (urlLower.contains("google.com/") || urlLower.contains("twitter.com")
                    || urlLower.contains("x.com/") || urlLower.contains("facebook.com")
                    || urlLower.contains("youtube.com") || urlLower.contains("justanswer.com")
                    || urlLower.contains("quora.com") || urlLower.contains("reddit.com")) continue;

            JsonNode title = item.path("title");
            String titleStr = title.isMissingNode() ? "" : title.asText("").toLowerCase();
            JsonNode snippet = item.path("snippet");
            String snippetStr = snippet.isMissingNode() ? "" : snippet.asText("").toLowerCase();
            String combined = titleStr + " " + snippetStr;

            if (firstResult == null) firstResult = url;

            boolean isFromPortal = !portalLower.isEmpty() && urlLower.contains(portalLower);
            boolean hasDocKeywords = combined.contains("invoice") || combined.contains("download")
                    || combined.contains("document") || combined.contains("boarding pass")
                    || combined.contains("receipt") || combined.contains("view")
                    || combined.contains("gst") || combined.contains("e-ticket");

            // Best: direct document page from the portal itself
            if (isFromPortal && hasDocKeywords && bestDirect == null) {
                bestDirect = url;
            }
            // Good: any page from the portal
            if (isFromPortal && bestPortal == null) {
                bestPortal = url;
            }
        }

        if (bestDirect != null) {
            log.info("SerpAPI best URL (direct document page): {}", bestDirect);
            return bestDirect;
        }
        if (bestPortal != null) {
            log.info("SerpAPI best URL (portal page): {}", bestPortal);
            return bestPortal;
        }
        if (firstResult != null) {
            log.info("SerpAPI best URL (first result): {}", firstResult);
            return firstResult;
        }
        return null;
    }

    private String extractFirstSnippet(JsonNode organicResults) {
        if (organicResults == null || !organicResults.isArray() || organicResults.isEmpty()) return null;
        JsonNode first = organicResults.get(0).path("snippet");
        return first.isMissingNode() ? null : first.asText(null);
    }
}
