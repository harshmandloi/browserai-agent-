package browserAI.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-based distributed lock service.
 * Prevents double execution of the same request.
 * Key format: agent:request:{userId}:{reference}
 */
@Service
public class RedisLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);
    private static final String KEY_PREFIX = "agent:request:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisLockService(StringRedisTemplate redisTemplate,
                            @Value("${redis-lock.ttl-seconds}") int ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Attempts to acquire a lock for a user request.
     * Returns true if lock acquired, false if request is already in progress.
     */
    public boolean acquireLock(String userId, String reference) {
        String key = buildKey(userId, reference);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "PROCESSING", ttl);
        if (Boolean.TRUE.equals(acquired)) {
            log.info("Lock acquired: {}", key);
            return true;
        }
        log.warn("Lock already held: {}", key);
        return false;
    }

    /**
     * Releases the lock for a user request.
     */
    public void releaseLock(String userId, String reference) {
        String key = buildKey(userId, reference);
        redisTemplate.delete(key);
        log.info("Lock released: {}", key);
    }

    /**
     * Checks if a request is currently locked (in progress).
     */
    public boolean isLocked(String userId, String reference) {
        String key = buildKey(userId, reference);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private String buildKey(String userId, String reference) {
        String ref = (reference != null && !reference.isBlank()) ? reference : "no-ref";
        return KEY_PREFIX + userId + ":" + ref;
    }
}
