package browserAI.demo.captcha;

/**
 * Supported CAPTCHA types that the solver can detect and handle.
 *
 * Detection priority (checked in this order):
 *   1. RECAPTCHA_V2 — iframe-based Google reCAPTCHA
 *   2. HCAPTCHA — iframe-based hCaptcha
 *   3. IMAGE_SELECTION — "Select all images with traffic lights" grid
 *   4. SLIDER — Drag to complete puzzle
 *   5. MATH — "What is 3 + 7?"
 *   6. TEXT_ALPHANUMERIC — Distorted text image (most common on Indian portals)
 *   7. UNKNOWN — Could not classify
 */
public enum CaptchaType {

    TEXT_ALPHANUMERIC("Distorted text/number image CAPTCHA"),
    IMAGE_SELECTION("Image grid selection CAPTCHA (select all buses, traffic lights, etc.)"),
    MATH("Math-based CAPTCHA (e.g., 3 + 7 = ?)"),
    SLIDER("Slider/drag CAPTCHA"),
    RECAPTCHA_V2("Google reCAPTCHA v2 (checkbox + optional image challenge)"),
    RECAPTCHA_V3("Google reCAPTCHA v3 (invisible, score-based)"),
    HCAPTCHA("hCaptcha (checkbox + image challenge)"),
    UNKNOWN("Unidentified CAPTCHA type");

    private final String description;

    CaptchaType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
