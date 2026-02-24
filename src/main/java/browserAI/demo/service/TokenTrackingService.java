package browserAI.demo.service;

import browserAI.demo.model.entity.TokenUsage;
import browserAI.demo.repository.TokenUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks Gemini API token usage per session, per user, and globally.
 * Dual storage: Redis (fast counters) + PostgreSQL (persistent history).
 */
@Service
public class TokenTrackingService {

    private static final Logger log = LoggerFactory.getLogger(TokenTrackingService.class);
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final Duration DAILY_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redisTemplate;
    private final TokenUsageRepository tokenUsageRepository;

    private final AtomicLong inMemoryTotal = new AtomicLong(0);

    public TokenTrackingService(StringRedisTemplate redisTemplate, TokenUsageRepository tokenUsageRepository) {
        this.redisTemplate = redisTemplate;
        this.tokenUsageRepository = tokenUsageRepository;
    }

    /**
     * Record token usage from a single Gemini API call.
     */
    public void recordUsage(String sessionId, String userId, int inputTokens, int outputTokens) {
        int total = inputTokens + outputTokens;
        log.debug("Token usage: session={}, input={}, output={}, total={}", sessionId, inputTokens, outputTokens, total);

        inMemoryTotal.addAndGet(total);

        try {
            String sessionInputKey = "tokens:session:" + sessionId + ":input";
            String sessionOutputKey = "tokens:session:" + sessionId + ":output";
            String sessionCallsKey = "tokens:session:" + sessionId + ":calls";
            String userKey = "tokens:user:" + userId + ":total";
            String dailyKey = "tokens:daily:" + LocalDate.now();
            String globalKey = "tokens:global:total";

            redisTemplate.opsForValue().increment(sessionInputKey, inputTokens);
            redisTemplate.opsForValue().increment(sessionOutputKey, outputTokens);
            redisTemplate.opsForValue().increment(sessionCallsKey, 1);
            redisTemplate.expire(sessionInputKey, SESSION_TTL);
            redisTemplate.expire(sessionOutputKey, SESSION_TTL);
            redisTemplate.expire(sessionCallsKey, SESSION_TTL);

            redisTemplate.opsForValue().increment(userKey, total);
            redisTemplate.opsForValue().increment(dailyKey, total);
            redisTemplate.expire(dailyKey, DAILY_TTL);
            redisTemplate.opsForValue().increment(globalKey, total);
        } catch (Exception e) {
            log.warn("Failed to record token usage in Redis: {}", e.getMessage());
        }
    }

    /**
     * Persist the session's accumulated token usage to PostgreSQL.
     * Called once at the end of a request execution.
     */
    public void persistSessionUsage(String sessionId, String userId, String portal,
                                    String documentType, int llmCalls) {
        try {
            String input = redisTemplate.opsForValue().get("tokens:session:" + sessionId + ":input");
            String output = redisTemplate.opsForValue().get("tokens:session:" + sessionId + ":output");
            long inputTokens = input != null ? Long.parseLong(input) : 0;
            long outputTokens = output != null ? Long.parseLong(output) : 0;
            long totalTokens = inputTokens + outputTokens;

            TokenUsage usage = new TokenUsage();
            usage.setSessionId(sessionId);
            usage.setUserId(userId);
            usage.setPortal(portal);
            usage.setDocumentType(documentType);
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            usage.setTotalTokens(totalTokens);
            usage.setLlmCalls(llmCalls);

            tokenUsageRepository.save(usage);
            log.info("Token usage persisted to DB: session={}, total={}, llmCalls={}", sessionId, totalTokens, llmCalls);
        } catch (Exception e) {
            log.warn("Failed to persist token usage to DB: {}", e.getMessage());
        }
    }

    /**
     * Get token usage for a specific session.
     */
    public Map<String, Object> getSessionUsage(String sessionId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String input = redisTemplate.opsForValue().get("tokens:session:" + sessionId + ":input");
            String output = redisTemplate.opsForValue().get("tokens:session:" + sessionId + ":output");
            String calls = redisTemplate.opsForValue().get("tokens:session:" + sessionId + ":calls");
            long inputTokens = input != null ? Long.parseLong(input) : 0;
            long outputTokens = output != null ? Long.parseLong(output) : 0;
            long llmCalls = calls != null ? Long.parseLong(calls) : 0;
            result.put("sessionId", sessionId);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("totalTokens", inputTokens + outputTokens);
            result.put("llmCalls", llmCalls);
        } catch (Exception e) {
            log.warn("Failed to get session token usage: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Get token usage for a specific user (DB + Redis).
     */
    public Map<String, Object> getUserUsage(String userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            long dbTotal = tokenUsageRepository.sumTotalTokensByUserId(userId);
            long dbCalls = tokenUsageRepository.sumLlmCallsByUserId(userId);
            String redisTotal = redisTemplate.opsForValue().get("tokens:user:" + userId + ":total");

            result.put("userId", userId);
            result.put("totalTokens_db", dbTotal);
            result.put("totalLlmCalls_db", dbCalls);
            result.put("totalTokens_redis", redisTotal != null ? Long.parseLong(redisTotal) : 0);
        } catch (Exception e) {
            log.warn("Failed to get user token usage: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Get global token usage stats (DB + Redis).
     */
    public Map<String, Object> getGlobalUsage() {
        Map<String, Object> result = new HashMap<>();
        try {
            long dbTotal = tokenUsageRepository.sumAllTokens();
            long dbCalls = tokenUsageRepository.sumAllLlmCalls();
            String daily = redisTemplate.opsForValue().get("tokens:daily:" + LocalDate.now());

            result.put("allTimeTotal_db", dbTotal);
            result.put("allTimeLlmCalls_db", dbCalls);
            result.put("todayTotal_redis", daily != null ? Long.parseLong(daily) : 0);
            result.put("inMemorySessionTotal", inMemoryTotal.get());
            result.put("date", LocalDate.now().toString());
        } catch (Exception e) {
            log.warn("Failed to get global token usage: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }
}
