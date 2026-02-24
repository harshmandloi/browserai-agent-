package browserAI.demo.model.entity;

import browserAI.demo.fsm.ConversationState;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists conversation/session state for multi-turn interactions.
 * State is also cached in Redis for fast access.
 */
@Entity
@Table(name = "conversation_sessions")
public class ConversationSession {

    @Id
    @Column(length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConversationState state;

    @Column(length = 100)
    private String portal;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(length = 500)
    private String reference;

    @Column(name = "pending_question", length = 1000)
    private String pendingQuestion;

    @Column(name = "llm_calls_count")
    private int llmCallsCount;

    @Column(name = "total_tokens_used")
    private long totalTokensUsed;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.expiresAt == null) {
            this.expiresAt = LocalDateTime.now().plusMinutes(30);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public ConversationState getState() { return state; }
    public void setState(ConversationState state) { this.state = state; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getPendingQuestion() { return pendingQuestion; }
    public void setPendingQuestion(String pendingQuestion) { this.pendingQuestion = pendingQuestion; }

    public int getLlmCallsCount() { return llmCallsCount; }
    public void setLlmCallsCount(int llmCallsCount) { this.llmCallsCount = llmCallsCount; }

    public long getTotalTokensUsed() { return totalTokensUsed; }
    public void setTotalTokensUsed(long totalTokensUsed) { this.totalTokensUsed = totalTokensUsed; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public void incrementLlmCalls() {
        this.llmCallsCount++;
    }

    public void addTokensUsed(long tokens) {
        this.totalTokensUsed += tokens;
    }
}
