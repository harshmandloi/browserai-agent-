package browserAI.demo.captcha;

/**
 * Result of a CAPTCHA solve attempt.
 */
public class CaptchaResult {

    private final boolean solved;
    private final CaptchaType type;
    private final String answer;
    private final int attempts;
    private final boolean manualRequired;
    private final String message;

    private CaptchaResult(boolean solved, CaptchaType type, String answer,
                          int attempts, boolean manualRequired, String message) {
        this.solved = solved;
        this.type = type;
        this.answer = answer;
        this.attempts = attempts;
        this.manualRequired = manualRequired;
        this.message = message;
    }

    public static CaptchaResult success(CaptchaType type, String answer, int attempts) {
        return new CaptchaResult(true, type, answer, attempts, false,
                "CAPTCHA solved via AI (%s) in %d attempt(s)".formatted(type, attempts));
    }

    public static CaptchaResult manualNeeded(CaptchaType type, int attempts) {
        return new CaptchaResult(false, type, null, attempts, true,
                "CAPTCHA could not be solved by AI after %d attempts. Manual solve required.".formatted(attempts));
    }

    public static CaptchaResult noCaptcha() {
        return new CaptchaResult(true, null, null, 0, false, "No CAPTCHA detected on page");
    }

    public static CaptchaResult failed(CaptchaType type, String reason) {
        return new CaptchaResult(false, type, null, 0, false, reason);
    }

    public boolean isSolved() { return solved; }
    public CaptchaType getType() { return type; }
    public String getAnswer() { return answer; }
    public int getAttempts() { return attempts; }
    public boolean isManualRequired() { return manualRequired; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "CaptchaResult{solved=%s, type=%s, attempts=%d, manual=%s, msg='%s'}"
                .formatted(solved, type, attempts, manualRequired, message);
    }
}
