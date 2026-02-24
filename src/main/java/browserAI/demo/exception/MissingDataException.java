package browserAI.demo.exception;

import java.util.List;

/**
 * Thrown when the agent discovers during exploration that it needs
 * additional data from the user (login credentials, specific fields, etc.).
 * Carries info about what fields are needed so the user gets a clear prompt.
 */
public class MissingDataException extends RuntimeException {

    private final String portal;
    private final List<String> missingFields;

    public MissingDataException(String portal, String message, List<String> missingFields) {
        super(message);
        this.portal = portal;
        this.missingFields = missingFields != null ? missingFields : List.of();
    }

    public MissingDataException(String portal, String message) {
        this(portal, message, List.of());
    }

    public String getPortal() { return portal; }
    public List<String> getMissingFields() { return missingFields; }
}
