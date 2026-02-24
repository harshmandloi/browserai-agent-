package browserAI.demo.service;

import browserAI.demo.model.entity.AuditLog;
import browserAI.demo.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Audit logging service — records every step of agent execution.
 * Required for: security compliance, debugging, execution trace.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Logs a step in the agent execution pipeline.
     */
    public void logStep(String sessionId, String userId, String action, String step,
                        String portal, String details, String status, Long durationMs) {
        AuditLog entry = new AuditLog();
        entry.setSessionId(sessionId);
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setStep(step);
        entry.setPortal(portal);
        entry.setDetails(maskSensitiveData(details));
        entry.setStatus(status);
        entry.setDurationMs(durationMs);

        auditLogRepository.save(entry);
        log.debug("Audit: [{}] {} - {} - {} ({}ms)", sessionId, action, step, status, durationMs);
    }

    /**
     * Convenience method for quick audit entries.
     */
    public void logStep(String sessionId, String userId, String action, String details, String status) {
        logStep(sessionId, userId, action, null, null, details, status, null);
    }

    /**
     * Retrieves full execution trace for a session.
     */
    public List<AuditLog> getSessionTrace(String sessionId) {
        return auditLogRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * Masks passwords, tokens, and other sensitive data from audit log details.
     */
    private String maskSensitiveData(String details) {
        if (details == null) return null;

        return details
                .replaceAll("(?i)(password|passwd|pwd|secret|token|key|credential)[\"']?\\s*[:=]\\s*[\"']?[^\"',\\s}]+",
                        "$1=***MASKED***")
                .replaceAll("(?i)(authorization|bearer)\\s+\\S+", "$1 ***MASKED***");
    }
}
