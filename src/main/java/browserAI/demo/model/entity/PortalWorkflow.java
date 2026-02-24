package browserAI.demo.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores learned/known portal workflows for memory/RAG retrieval.
 * When a portal is successfully automated, the workflow steps are stored here.
 * On future requests for the same portal, these stored steps serve as hints to the LLM.
 */
@Entity
@Table(name = "portal_workflows")
public class PortalWorkflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String portal;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(name = "login_url", length = 1000)
    private String loginUrl;

    @Column(name = "workflow_steps", nullable = false, columnDefinition = "TEXT")
    private String workflowSteps;

    @Column(name = "css_selectors", columnDefinition = "TEXT")
    private String cssSelectors;

    @Column(name = "success_count")
    private int successCount;

    @Column(name = "failure_count")
    private int failureCount;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getLoginUrl() { return loginUrl; }
    public void setLoginUrl(String loginUrl) { this.loginUrl = loginUrl; }

    public String getWorkflowSteps() { return workflowSteps; }
    public void setWorkflowSteps(String workflowSteps) { this.workflowSteps = workflowSteps; }

    public String getCssSelectors() { return cssSelectors; }
    public void setCssSelectors(String cssSelectors) { this.cssSelectors = cssSelectors; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(LocalDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }

    public LocalDateTime getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(LocalDateTime lastFailureAt) { this.lastFailureAt = lastFailureAt; }

    public boolean isVerified() { return isVerified; }
    public void setVerified(boolean verified) { isVerified = verified; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void recordSuccess() {
        this.successCount++;
        this.lastSuccessAt = LocalDateTime.now();
    }

    public void recordFailure() {
        this.failureCount++;
        this.lastFailureAt = LocalDateTime.now();
    }
}
