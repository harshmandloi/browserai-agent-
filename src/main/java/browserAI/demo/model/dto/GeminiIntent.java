package browserAI.demo.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiIntent {

    private String portal;
    private String documentType;
    private String reference;
    private String email;
    private String lastName;

    // Phase 1 additions
    private String intentType;        // download, query, batch, schedule
    private List<String> references;  // for batch downloads (multiple PNRs/refs)
    private String schedule;          // natural language schedule: "every month on 5th"
    private String query;             // for document queries: "total amount on last invoice"

    public GeminiIntent() {}

    public GeminiIntent(String portal, String documentType, String reference) {
        this.portal = portal;
        this.documentType = documentType;
        this.reference = reference;
    }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getIntentType() { return intentType; }
    public void setIntentType(String intentType) { this.intentType = intentType; }

    public List<String> getReferences() { return references; }
    public void setReferences(List<String> references) { this.references = references; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    /**
     * Returns the effective intent type, defaulting to "download" for backward compatibility.
     */
    public String effectiveIntentType() {
        if (intentType == null || intentType.isBlank()) return "download";
        return intentType.toLowerCase().trim();
    }

    /**
     * True if this is a batch request with multiple references.
     */
    public boolean isBatch() {
        return "batch".equals(effectiveIntentType())
                || (references != null && references.size() > 1);
    }

    public boolean isValid() {
        return portal != null && !portal.isBlank()
            && documentType != null && !documentType.isBlank();
    }

    @Override
    public String toString() {
        return "GeminiIntent{intentType='%s', portal='%s', documentType='%s', reference='%s', references=%s, email='%s', lastName='%s', schedule='%s', query='%s'}"
                .formatted(effectiveIntentType(), portal, documentType, reference, references, email, lastName, schedule, query);
    }
}
