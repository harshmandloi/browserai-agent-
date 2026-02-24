package browserAI.demo.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentResponse {

    private boolean success;
    private String message;
    private String requestId;
    private DocumentInfo document;
    private String error;
    private Boolean otpRequired;
    private Boolean dynamicMode;
    private String sessionId;
    private Map<String, Object> tokenUsage;
    private List<String> requiredFields;
    private Map<String, Object> extractedData;
    private List<BatchResult> batchResults;
    private ScheduleInfo scheduleInfo;
    private LocalDateTime timestamp;

    private AgentResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public static AgentResponse success(String message, DocumentInfo document) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.message = message;
        response.document = document;
        return response;
    }

    public static AgentResponse error(String error) {
        AgentResponse response = new AgentResponse();
        response.success = false;
        response.error = error;
        return response;
    }

    public static AgentResponse otpNeeded(String sessionId, String message) {
        AgentResponse response = new AgentResponse();
        response.success = false;
        response.otpRequired = true;
        response.sessionId = sessionId;
        response.message = message;
        return response;
    }

    public static AgentResponse dynamicExploration(String sessionId, String message) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.dynamicMode = true;
        response.sessionId = sessionId;
        response.message = message;
        return response;
    }

    public static AgentResponse needsInput(String sessionId, String message, List<String> requiredFields) {
        AgentResponse response = new AgentResponse();
        response.success = false;
        response.sessionId = sessionId;
        response.message = message;
        response.requiredFields = requiredFields;
        return response;
    }

    public static AgentResponse queryResult(String message, Map<String, Object> extractedData) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.message = message;
        response.extractedData = extractedData;
        return response;
    }

    public static AgentResponse batchResult(String message, List<BatchResult> results) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.message = message;
        response.batchResults = results;
        return response;
    }

    public static AgentResponse scheduleCreated(String message, ScheduleInfo info) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.message = message;
        response.scheduleInfo = info;
        return response;
    }

    // Getters and setters

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public DocumentInfo getDocument() { return document; }
    public String getError() { return error; }
    public Boolean getOtpRequired() { return otpRequired; }
    public Boolean getDynamicMode() { return dynamicMode; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Map<String, Object> getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(Map<String, Object> tokenUsage) { this.tokenUsage = tokenUsage; }

    public List<String> getRequiredFields() { return requiredFields; }

    public Map<String, Object> getExtractedData() { return extractedData; }
    public void setExtractedData(Map<String, Object> extractedData) { this.extractedData = extractedData; }

    public List<BatchResult> getBatchResults() { return batchResults; }
    public void setBatchResults(List<BatchResult> batchResults) { this.batchResults = batchResults; }

    public ScheduleInfo getScheduleInfo() { return scheduleInfo; }
    public void setScheduleInfo(ScheduleInfo scheduleInfo) { this.scheduleInfo = scheduleInfo; }

    public LocalDateTime getTimestamp() { return timestamp; }

    /**
     * Nested DTO for document metadata in the response.
     */
    public static class DocumentInfo {
        private Long id;
        private String portal;
        private String documentType;
        private String reference;
        private String hash;
        private String fileName;
        private String filePath;
        private String downloadUrl;
        private Long fileSizeBytes;
        private boolean duplicate;
        private String localDownloadPath;

        public DocumentInfo() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getPortal() { return portal; }
        public void setPortal(String portal) { this.portal = portal; }
        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getHash() { return hash; }
        public void setHash(String hash) { this.hash = hash; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
        public Long getFileSizeBytes() { return fileSizeBytes; }
        public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
        public boolean isDuplicate() { return duplicate; }
        public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
        public String getLocalDownloadPath() { return localDownloadPath; }
        public void setLocalDownloadPath(String localDownloadPath) { this.localDownloadPath = localDownloadPath; }
    }

    /**
     * Result of a single item in a batch download.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BatchResult {
        private String reference;
        private boolean success;
        private String message;
        private DocumentInfo document;
        private String error;

        public BatchResult() {}

        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public DocumentInfo getDocument() { return document; }
        public void setDocument(DocumentInfo document) { this.document = document; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * Info about a created/managed scheduled task.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScheduleInfo {
        private Long scheduleId;
        private String cronExpression;
        private String description;
        private String status;
        private String nextRunAt;

        public ScheduleInfo() {}

        public Long getScheduleId() { return scheduleId; }
        public void setScheduleId(Long scheduleId) { this.scheduleId = scheduleId; }
        public String getCronExpression() { return cronExpression; }
        public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNextRunAt() { return nextRunAt; }
        public void setNextRunAt(String nextRunAt) { this.nextRunAt = nextRunAt; }
    }
}
