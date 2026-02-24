package browserAI.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-user rate limiting using Redis sliding window.
 * Prevents abuse and protects portal accounts from being flagged.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String RATE_KEY_PREFIX = "ratelimit:user:";
    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final int MAX_REQUESTS_PER_HOUR = 60;
    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration HOUR_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks if the user is within rate limits.
     * Uses two windows: per-minute and per-hour.
     *
     * @return true if request is allowed, false if rate limited
     */
    public boolean isAllowed(String userId) {
        boolean minuteOk = checkWindow(userId, "minute", MAX_REQUESTS_PER_MINUTE, MINUTE_WINDOW);
        boolean hourOk = checkWindow(userId, "hour", MAX_REQUESTS_PER_HOUR, HOUR_WINDOW);

        if (!minuteOk) {
            log.warn("Rate limit exceeded (per-minute) for user: {}", userId);
            return false;
        }
        if (!hourOk) {
            log.warn("Rate limit exceeded (per-hour) for user: {}", userId);
            return false;
        }
        return true;
    }

    /**
     * Records a request for rate limiting counters.
     */
    public void recordRequest(String userId) {
        incrementWindow(userId, "minute", MINUTE_WINDOW);
        incrementWindow(userId, "hour", HOUR_WINDOW);
    }

    /**
     * Returns remaining requests in the minute window.
     */
    public int getRemainingMinute(String userId) {
        String key = RATE_KEY_PREFIX + userId + ":minute";
        String val = redisTemplate.opsForValue().get(key);
        int used = val != null ? Integer.parseInt(val) : 0;
        return Math.max(0, MAX_REQUESTS_PER_MINUTE - used);
    }

    private boolean checkWindow(String userId, String window, int maxRequests, Duration ttl) {
        String key = RATE_KEY_PREFIX + userId + ":" + window;
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return true;
        return Integer.parseInt(val) < maxRequests;
    }

    private void incrementWindow(String userId, String window, Duration ttl) {
        String key = RATE_KEY_PREFIX + userId + ":" + window;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, ttl);
        }
    }
}
