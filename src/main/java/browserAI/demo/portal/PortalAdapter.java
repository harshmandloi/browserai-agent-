package browserAI.demo.portal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interface for portal-specific browser automation.
 * Each portal (Amazon, Demo, etc.) implements this to handle its unique login/download flow.
 */
public interface PortalAdapter {

    String getPortalName();

    File downloadDocument(String username, String password, String documentType, String reference);

    /**
     * Returns the navigation steps recorded during the last execution.
     * Used by the orchestrator to cache steps for future reuse (saves LLM tokens).
     */
    default List<NavigationStep> getLastNavigationSteps() {
        return List.of();
    }

    /**
     * Represents a single step in the portal navigation flow.
     */
    record NavigationStep(String action, String url, String selector, String description) {
        public String toCompact() {
            StringBuilder sb = new StringBuilder(action);
            if (url != null) sb.append("|url=").append(url);
            if (selector != null) sb.append("|sel=").append(selector);
            if (description != null) sb.append("|").append(description);
            return sb.toString();
        }

        public static NavigationStep of(String action, String url, String selector, String description) {
            return new NavigationStep(action, url, selector, description);
        }
    }

    /**
     * Thread-safe step recorder that adapters can use.
     */
    class StepRecorder {
        private static final Map<Long, List<NavigationStep>> THREAD_STEPS = new ConcurrentHashMap<>();

        public static void clear() {
            THREAD_STEPS.remove(Thread.currentThread().getId());
        }

        public static void record(String action, String url, String selector, String description) {
            THREAD_STEPS.computeIfAbsent(Thread.currentThread().getId(), k -> new ArrayList<>())
                    .add(NavigationStep.of(action, url, selector, description));
        }

        public static List<NavigationStep> getSteps() {
            return THREAD_STEPS.getOrDefault(Thread.currentThread().getId(), List.of());
        }

        public static String toCompactString() {
            List<NavigationStep> steps = getSteps();
            if (steps.isEmpty()) return "";
            return steps.stream()
                    .map(NavigationStep::toCompact)
                    .reduce((a, b) -> a + " -> " + b)
                    .orElse("");
        }
    }
}
