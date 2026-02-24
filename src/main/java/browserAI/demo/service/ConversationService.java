package browserAI.demo.service;

import browserAI.demo.fsm.ConversationState;
import browserAI.demo.fsm.StateTransition;
import browserAI.demo.model.entity.ConversationSession;
import browserAI.demo.repository.ConversationSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversation / Session Service — manages FSM state transitions.
 *
 * State is dual-stored:
 * - Redis: fast read/write for active sessions (cached)
 * - Postgres: durable storage for audit and history
 *
 * Handles multi-turn conversations where the agent needs to ask follow-up
 * questions (missing portal, missing reference, credential setup, etc.)
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final ConversationSessionRepository sessionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationSessionRepository sessionRepository,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new conversation session.
     */
    public ConversationSession createSession(String userId) {
        ConversationSession session = new ConversationSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        session.setUserId(userId);
        session.setState(ConversationState.INIT);
        session.setLlmCallsCount(0);
        session.setTotalTokensUsed(0);
        session.setRetryCount(0);

        session = sessionRepository.save(session);
        cacheSession(session);

        log.info("Session created: {} for user: {}", session.getSessionId(), userId);
        return session;
    }

    /**
     * Retrieves session — first from Redis cache, fallback to Postgres.
     */
    public Optional<ConversationSession> getSession(String sessionId) {
        // Try Redis first
        Optional<ConversationSession> cached = getCachedSession(sessionId);
        if (cached.isPresent()) {
            return cached;
        }

        // Fallback to Postgres
        Optional<ConversationSession> fromDb = sessionRepository.findBySessionId(sessionId);
        fromDb.ifPresent(this::cacheSession);
        return fromDb;
    }

    /**
     * Transitions session state with validation.
     * Validates the transition is legal per FSM rules.
     */
    public ConversationSession transition(ConversationSession session, ConversationState newState) {
        ConversationState oldState = session.getState();
        StateTransition.validate(oldState, newState);

        session.setState(newState);
        session = sessionRepository.save(session);
        cacheSession(session);

        log.info("Session {} transitioned: {} -> {}", session.getSessionId(), oldState, newState);
        return session;
    }

    /**
     * Updates session with intent data after Gemini extraction.
     */
    public ConversationSession updateIntent(ConversationSession session, String portal,
                                            String documentType, String reference) {
        session.setPortal(portal);
        session.setDocumentType(documentType);
        session.setReference(reference);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    /**
     * Sets a pending question for the user (multi-turn flow).
     */
    public ConversationSession setPendingQuestion(ConversationSession session, String question) {
        session.setPendingQuestion(question);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    /**
     * Records an LLM call against the session (for governance tracking).
     */
    public ConversationSession recordLlmCall(ConversationSession session, long tokensUsed) {
        session.incrementLlmCalls();
        session.addTokensUsed(tokensUsed);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    /**
     * Records a retry attempt.
     */
    public ConversationSession recordRetry(ConversationSession session) {
        session.setRetryCount(session.getRetryCount() + 1);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    /**
     * Marks session as failed with error message.
     */
    public ConversationSession markFailed(ConversationSession session, String errorMessage) {
        session.setState(ConversationState.FAILURE);
        session.setErrorMessage(errorMessage);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    /**
     * Marks session as successful.
     */
    public ConversationSession markSuccess(ConversationSession session) {
        session.setState(ConversationState.SUCCESS);
        session = sessionRepository.save(session);
        cacheSession(session);
        return session;
    }

    // ==================== Redis Cache ====================

    private void cacheSession(ConversationSession session) {
        try {
            String key = SESSION_KEY_PREFIX + session.getSessionId();
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache session in Redis: {}", e.getMessage());
        }
    }

    private Optional<ConversationSession> getCachedSession(String sessionId) {
        try {
            String key = SESSION_KEY_PREFIX + sessionId;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, ConversationSession.class));
            }
        } catch (Exception e) {
            log.warn("Failed to read session from Redis: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
