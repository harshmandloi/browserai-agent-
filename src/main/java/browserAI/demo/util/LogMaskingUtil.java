package browserAI.demo.util;

import java.util.regex.Pattern;

/**
 * Comprehensive log masking utility.
 * Ensures passwords, tokens, API keys, and other sensitive data
 * are NEVER written to logs in plain text.
 */
public final class LogMaskingUtil {

    private LogMaskingUtil() {}

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|api[_-]?key|token|authorization|bearer|credential)\\s*[=:]\\s*[\"']?([^\"'\\s,;}{\\]]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
    );

    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(\\d{4})[- ]?(\\d{4})[- ]?(\\d{4})[- ]?(\\d{4})\\b"
    );

    /**
     * Masks all sensitive data in a string for safe logging.
     */
    public static String mask(String input) {
        if (input == null) return null;

        String masked = PASSWORD_PATTERN.matcher(input).replaceAll("$1=***MASKED***");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("$1***@$2");
        masked = CREDIT_CARD_PATTERN.matcher(masked).replaceAll("$1-****-****-$4");

        return masked;
    }

    /**
     * Masks a specific value completely, showing only first and last 2 chars.
     */
    public static String maskValue(String value) {
        if (value == null) return null;
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * Masks username/email for logging (shows first 3 chars).
     */
    public static String maskUsername(String username) {
        if (username == null) return null;
        if (username.length() <= 3) return "***";
        return username.substring(0, 3) + "***";
    }
}
