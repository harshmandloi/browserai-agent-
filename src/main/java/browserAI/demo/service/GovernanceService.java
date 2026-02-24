package browserAI.demo.service;

import browserAI.demo.model.entity.ConversationSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Governance controls — guardrails for the AI agent.
 * All limits are configurable via application.yml for production tuning.
 * Increased defaults to support slow government portals and complex flows.
 */
@Service
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    @Value("${agent.max-retries:3}")
    private int maxRetries;

    @Value("${governance.max-llm-calls:25}")
    private int maxLlmCallsPerSession;

    @Value("${governance.max-tokens:100000}")
    private long maxTokensPerSession;

    @Value("${governance.max-exploration-depth:15}")
    private int maxDomExplorationDepth;

    @Value("${governance.max-session-duration-ms:600000}")
    private long maxSessionDurationMs;

    public void checkLlmCallAllowed(ConversationSession session) {
        if (session.getLlmCallsCount() >= maxLlmCallsPerSession) {
            String msg = "Max LLM calls exceeded (%d/%d) for session %s"
                    .formatted(session.getLlmCallsCount(), maxLlmCallsPerSession, session.getSessionId());
            log.error(msg);
            throw new GovernanceViolationException(msg);
        }

        if (session.getTotalTokensUsed() >= maxTokensPerSession) {
            String msg = "Token budget exceeded (%d/%d) for session %s"
                    .formatted(session.getTotalTokensUsed(), maxTokensPerSession, session.getSessionId());
            log.error(msg);
            throw new GovernanceViolationException(msg);
        }

        if (session.isExpired()) {
            String msg = "Session expired for session %s".formatted(session.getSessionId());
            log.error(msg);
            throw new GovernanceViolationException(msg);
        }
    }

    public boolean canRetry(ConversationSession session) {
        return session.getRetryCount() < maxRetries;
    }

    public void checkExplorationDepth(int currentDepth) {
        if (currentDepth >= maxDomExplorationDepth) {
            throw new GovernanceViolationException(
                    "Max DOM exploration depth reached (%d/%d). Stopping exploration."
                            .formatted(currentDepth, maxDomExplorationDepth));
        }
    }

    public void checkSessionDuration(long startTimeMs) {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed > maxSessionDurationMs) {
            throw new GovernanceViolationException(
                    "Max session duration exceeded (%dms / %dms)".formatted(elapsed, maxSessionDurationMs));
        }
    }

    public void checkRepeatedAction(String actionKey, int count) {
        int repeatedActionThreshold = 5;
        if (count >= repeatedActionThreshold) {
            throw new GovernanceViolationException(
                    "Possible infinite loop detected: action '%s' repeated %d times".formatted(actionKey, count));
        }
    }

    public long getMaxSessionDurationMs() {
        return maxSessionDurationMs;
    }

    public static class GovernanceViolationException extends RuntimeException {
        public GovernanceViolationException(String message) {
            super(message);
        }
    }
}
