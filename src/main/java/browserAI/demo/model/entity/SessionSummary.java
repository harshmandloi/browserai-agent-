package browserAI.demo.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores summarized session data for RAG/memory retrieval.
 * Uses pgvector embedding column for semantic similarity search.
 * Past session summaries help the LLM make better decisions for similar portals.
 */
@Entity
@Table(name = "session_summaries")
public class SessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 100)
    private String portal;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "steps_taken", columnDefinition = "TEXT")
    private String stepsTaken;

    @Column(name = "was_successful", nullable = false)
    private boolean wasSuccessful;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;

    @Column(name = "llm_calls_count")
    private int llmCallsCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public String getStepsTaken() { return stepsTaken; }
    public void setStepsTaken(String stepsTaken) { this.stepsTaken = stepsTaken; }

    public boolean isWasSuccessful() { return wasSuccessful; }
    public void setWasSuccessful(boolean wasSuccessful) { this.wasSuccessful = wasSuccessful; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public int getLlmCallsCount() { return llmCallsCount; }
    public void setLlmCallsCount(int llmCallsCount) { this.llmCallsCount = llmCallsCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
