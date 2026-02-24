package browserAI.demo.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Pure dynamic factory — ALL portals route through DynamicPortalAdapter.
 * No hardcoded adapters. The LLM figures out navigation for every portal.
 */
@Component
public class PortalExecutorFactory {

    private static final Logger log = LoggerFactory.getLogger(PortalExecutorFactory.class);

    private final DynamicPortalAdapter dynamicPortalAdapter;

    public PortalExecutorFactory(DynamicPortalAdapter dynamicPortalAdapter) {
        this.dynamicPortalAdapter = dynamicPortalAdapter;
        log.info("PortalExecutorFactory initialized — PURE DYNAMIC MODE (LLM-guided for all portals)");
    }

    public PortalAdapter getAdapter(String portal) {
        String portalLower = portal.toLowerCase().trim();
        log.info("Resolving adapter for '{}' → DynamicPortalAdapter (LLM-guided)", portalLower);

        return new PortalAdapter() {
            @Override
            public String getPortalName() {
                return portalLower;
            }

            @Override
            public File downloadDocument(String username, String password, String documentType, String reference) {
                return dynamicPortalAdapter.downloadDocument(portalLower, username, password, documentType, reference);
            }

            @Override
            public java.util.List<NavigationStep> getLastNavigationSteps() {
                return StepRecorder.getSteps();
            }
        };
    }

    public boolean isSupported(String portal) {
        return true;
    }

    public boolean hasSpecificAdapter(String portal) {
        return false;
    }
}
