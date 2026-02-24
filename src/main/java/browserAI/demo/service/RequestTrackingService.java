package browserAI.demo.service;

import browserAI.demo.model.entity.AgentRequestLog;
import browserAI.demo.repository.AgentRequestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages request tracking with unique IDs in format: AI-{PORTAL_CODE}-{SEQUENCE}
 * Every request is logged in the database for audit and analytics.
 */
@Service
public class RequestTrackingService {

    private static final Logger log = LoggerFactory.getLogger(RequestTrackingService.class);

    private final AgentRequestLogRepository requestLogRepository;

    /**
     * Auto-generates a 3-letter portal code from any portal name.
     * No hardcoded mapping needed — works for ANY portal dynamically.
     */
    private String derivePortalCode(String portal) {
        if (portal == null || portal.isBlank()) return "GEN";
        String clean = portal.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (clean.length() >= 3) {
            return clean.substring(0, 3).toUpperCase();
        }
        return (clean + "XX").substring(0, 3).toUpperCase();
    }

    private final ConcurrentHashMap<String, AtomicLong> portalCounters = new ConcurrentHashMap<>();

    public RequestTrackingService(AgentRequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
        initializeCounters();
    }

    private void initializeCounters() {
        long totalCount = requestLogRepository.getTotalRequestCount();
        log.info("[RequestTracking] Initialized — total requests in DB: {}", totalCount);
    }

    /**
     * Generates a unique request ID in format: AI-{PORTAL_CODE}-{SEQUENCE}
     */
    public String generateRequestId(String portal) {
        String portalCode = derivePortalCode(portal);

        AtomicLong counter = portalCounters.computeIfAbsent(portalCode, k -> {
            long maxId = requestLogRepository.getTotalRequestCount();
            return new AtomicLong(maxId);
        });

        long seq = counter.incrementAndGet();
        return "AI-%s-%06d".formatted(portalCode, seq);
    }

    /**
     * Creates a new request log entry and returns the tracking ID.
     */
    public AgentRequestLog createRequestLog(String userId, String input, String intentType,
                                             String portal, String documentType, String reference) {
        String requestId = generateRequestId(portal);

        AgentRequestLog entry = new AgentRequestLog();
        entry.setRequestId(requestId);
        entry.setUserId(userId);
        entry.setInputText(input);
        entry.setIntentType(intentType);
        entry.setPortal(portal);
        entry.setDocumentType(documentType);
        entry.setReference(reference);
        entry.setStatus("PROCESSING");

        AgentRequestLog saved = requestLogRepository.save(entry);
        log.info("[RequestTracking] Created request: {} (userId={}, portal={}, type={})",
                requestId, userId, portal, intentType);
        return saved;
    }

    /**
     * Marks a request as completed with result details.
     */
    public void completeRequest(String requestId, String status, String message,
                                 Long documentId, Long durationMs, Long totalTokens, String sessionId) {
        requestLogRepository.findByRequestId(requestId).ifPresent(entry -> {
            entry.setStatus(status);
            entry.setResultMessage(message);
            entry.setDocumentId(documentId);
            entry.setDurationMs(durationMs);
            entry.setTotalTokens(totalTokens);
            entry.setSessionId(sessionId);
            entry.setCompletedAt(LocalDateTime.now());
            requestLogRepository.save(entry);
            log.info("[RequestTracking] Completed: {} — status={}, duration={}ms", requestId, status, durationMs);
        });
    }

    public List<AgentRequestLog> getUserRequests(String userId) {
        return requestLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public java.util.Optional<AgentRequestLog> getByRequestId(String requestId) {
        return requestLogRepository.findByRequestId(requestId);
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "totalRequests", requestLogRepository.getTotalRequestCount(),
            "successfulRequests", requestLogRepository.getSuccessCount()
        );
    }
}
