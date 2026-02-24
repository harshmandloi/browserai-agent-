package browserAI.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * OTP Service — handles OTP submission and waiting for browser automation.
 *
 * Flow:
 *   1. Browser detects OTP page → calls otpService.waitForOtp(userId, timeout)
 *   2. waitForOtp() polls Redis every 2 seconds for the OTP value
 *   3. Meanwhile, user submits OTP via POST /api/otp → otpService.submitOtp(userId, otp)
 *   4. submitOtp() writes to Redis
 *   5. waitForOtp() picks it up → returns to browser adapter → fills OTP
 *
 * Redis key format: otp:{userId}
 * TTL: 3 minutes (auto-cleanup)
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final String OTP_KEY_PREFIX = "otp:";
    private static final Duration OTP_TTL = Duration.ofMinutes(3);
    private static final int POLL_INTERVAL_MS = 2000;

    private final StringRedisTemplate redisTemplate;

    public OtpService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Called by the user via API — submits OTP for a pending session.
     */
    public void submitOtp(String userId, String otp) {
        String key = OTP_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, otp, OTP_TTL);
        log.info("OTP submitted for user: {}", userId);
    }

    /**
     * Called by PortalAdapter — blocks until OTP is available or timeout.
     * Polls Redis every 2 seconds.
     *
     * @param userId        the user who needs to provide OTP
     * @param timeoutSeconds max seconds to wait
     * @return the OTP string, or null if timeout
     */
    public String waitForOtp(String userId, int timeoutSeconds) {
        String key = OTP_KEY_PREFIX + userId;
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        log.info("Waiting for OTP for user: {} (timeout: {}s)", userId, timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            String otp = redisTemplate.opsForValue().get(key);
            if (otp != null && !otp.isBlank()) {
                redisTemplate.delete(key);
                log.info("OTP received for user: {}", userId);
                return otp;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        log.warn("OTP timeout for user: {}", userId);
        return null;
    }

    /**
     * Checks if an OTP request is pending for a user.
     */
    public boolean isOtpPending(String userId) {
        String key = OTP_KEY_PREFIX + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Clears any pending OTP for a user.
     */
    public void clearOtp(String userId) {
        redisTemplate.delete(OTP_KEY_PREFIX + userId);
    }
}
