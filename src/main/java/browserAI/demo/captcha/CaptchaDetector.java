package browserAI.demo.captcha;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the presence and type of CAPTCHA on a Playwright page.
 *
 * Detection strategy (ordered by specificity):
 *   1. Check for reCAPTCHA iframe/div
 *   2. Check for hCaptcha iframe/div
 *   3. Check for image selection grid
 *   4. Check for slider elements
 *   5. Check for math-based text
 *   6. Check for text/alphanumeric CAPTCHA image
 *   7. No CAPTCHA found
 */
public class CaptchaDetector {

    private static final Logger log = LoggerFactory.getLogger(CaptchaDetector.class);

    private CaptchaDetector() {}

    /**
     * Detects if any CAPTCHA is present on the page.
     * @return the detected CaptchaType, or null if no CAPTCHA found
     */
    public static CaptchaType detect(Page page) {
        try {
            if (hasRecaptchaV3(page)) {
                log.info("[CaptchaDetector] reCAPTCHA v3 (invisible) detected");
                return CaptchaType.RECAPTCHA_V3;
            }

            if (hasRecaptcha(page)) {
                log.info("[CaptchaDetector] reCAPTCHA v2 detected");
                return CaptchaType.RECAPTCHA_V2;
            }

            if (hasHCaptcha(page)) {
                log.info("[CaptchaDetector] hCaptcha detected");
                return CaptchaType.HCAPTCHA;
            }

            if (hasImageSelection(page)) {
                log.info("[CaptchaDetector] Image selection CAPTCHA detected");
                return CaptchaType.IMAGE_SELECTION;
            }

            if (hasSlider(page)) {
                log.info("[CaptchaDetector] Slider CAPTCHA detected");
                return CaptchaType.SLIDER;
            }

            if (hasMathCaptcha(page)) {
                log.info("[CaptchaDetector] Math CAPTCHA detected");
                return CaptchaType.MATH;
            }

            if (hasTextCaptcha(page)) {
                log.info("[CaptchaDetector] Text/alphanumeric CAPTCHA detected");
                return CaptchaType.TEXT_ALPHANUMERIC;
            }

            return null;

        } catch (Exception e) {
            log.warn("[CaptchaDetector] Detection failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Quick check — is there ANY CAPTCHA on this page?
     */
    public static boolean isCaptchaPresent(Page page) {
        return detect(page) != null;
    }

    private static boolean hasRecaptchaV3(Page page) {
        try {
            // reCAPTCHA v3 is invisible — detected by badge or script with render parameter
            boolean hasBadge = countVisible(page, ".grecaptcha-badge") > 0;
            if (hasBadge) return true;

            // Check for reCAPTCHA v3 script in page
            Object hasV3Script = page.evaluate("""
                (() => {
                    const scripts = document.querySelectorAll('script[src*="recaptcha"]');
                    for (const s of scripts) {
                        if (s.src.includes('render=') && !s.src.includes('render=explicit')) return true;
                    }
                    return typeof grecaptcha !== 'undefined' && typeof grecaptcha.execute === 'function';
                })()
            """);
            return Boolean.TRUE.equals(hasV3Script);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasRecaptcha(Page page) {
        return countVisible(page, String.join(", ",
                "iframe[src*='recaptcha']",
                "iframe[src*='google.com/recaptcha']",
                ".g-recaptcha",
                "#recaptcha",
                "[data-sitekey]"
        )) > 0;
    }

    private static boolean hasHCaptcha(Page page) {
        return countVisible(page, String.join(", ",
                "iframe[src*='hcaptcha']",
                ".h-captcha",
                "[data-hcaptcha-widget-id]"
        )) > 0;
    }

    private static boolean hasImageSelection(Page page) {
        return countVisible(page, String.join(", ",
                ".rc-imageselect",
                "table.rc-imageselect-table",
                "[class*='image-select']",
                "[class*='captcha-grid']",
                "div[class*='captcha'] img + img + img"
        )) > 0;
    }

    private static boolean hasSlider(Page page) {
        return countVisible(page, String.join(", ",
                "[class*='slider-captcha']",
                "[class*='slide-verify']",
                "[class*='captcha-slider']",
                "div[class*='slider'] canvas",
                ".geetest_slider"
        )) > 0;
    }

    private static boolean hasMathCaptcha(Page page) {
        try {
            Locator captchaTexts = page.locator(
                    "label:has-text('+'), label:has-text('='), span:has-text('+'), " +
                    "[class*='captcha']:has-text('+'), [class*='captcha']:has-text('=')");
            if (captchaTexts.count() > 0) {
                String text = captchaTexts.first().innerText();
                return text.matches(".*\\d+\\s*[+\\-×x*]\\s*\\d+\\s*=?.*");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasTextCaptcha(Page page) {
        return countVisible(page, String.join(", ",
                "img[src*='captcha' i]",
                "img[alt*='captcha' i]",
                "img[id*='captcha' i]",
                "img[class*='captcha' i]",
                "canvas[id*='captcha' i]",
                "canvas[class*='captcha' i]",
                "#captchaImage",
                "#captcha_image",
                ".captcha-image",
                "img[src*='verify']",
                "img[src*='code']"
        )) > 0 && hasAdjacentCaptchaInput(page);
    }

    /**
     * Checks if there's an input field near the CAPTCHA image (for text entry).
     */
    private static boolean hasAdjacentCaptchaInput(Page page) {
        return countVisible(page, String.join(", ",
                "input[name*='captcha' i]",
                "input[id*='captcha' i]",
                "input[placeholder*='captcha' i]",
                "input[placeholder*='code' i]",
                "input[placeholder*='image' i]",
                "input[aria-label*='captcha' i]"
        )) > 0;
    }

    /**
     * Tries to locate the CAPTCHA image element for screenshot capture.
     * Returns the first visible match, or null.
     */
    public static Locator findCaptchaImageElement(Page page) {
        String[] selectors = {
                "img[src*='captcha' i]",
                "img[alt*='captcha' i]",
                "img[id*='captcha' i]",
                "img[class*='captcha' i]",
                "canvas[id*='captcha' i]",
                "canvas[class*='captcha' i]",
                "#captchaImage",
                "#captcha_image",
                ".captcha-image",
                "img[src*='verify']"
        };

        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Tries to locate the CAPTCHA input field.
     */
    public static Locator findCaptchaInputField(Page page) {
        String[] selectors = {
                "input[name*='captcha' i]",
                "input[id*='captcha' i]",
                "input[placeholder*='captcha' i]",
                "input[placeholder*='enter the code' i]",
                "input[placeholder*='enter code' i]",
                "input[placeholder*='type the' i]",
                "input[aria-label*='captcha' i]"
        };

        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int countVisible(Page page, String selector) {
        try {
            Locator loc = page.locator(selector);
            int count = 0;
            for (int i = 0; i < loc.count() && i < 5; i++) {
                try {
                    if (loc.nth(i).isVisible()) count++;
                } catch (Exception ignored) {}
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
