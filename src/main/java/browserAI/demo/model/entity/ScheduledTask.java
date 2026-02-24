package browserAI.demo.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores scheduled tasks — both recurring (cron) and one-time (triggerAt).
 * Supports auto web check-in (one-time, 24h before flight) and recurring downloads.
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "request_id", nullable = false, length = 30)
    private String requestId;

    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    @Column(length = 100)
    private String portal;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(length = 500)
    private String reference;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "schedule_description", length = 500)
    private String scheduleDescription;

    @Column(name = "task_type", nullable = false, length = 20)
    private String taskType = "RECURRING";

    @Column(name = "trigger_at")
    private LocalDateTime triggerAt;

    @Column(name = "flight_departure")
    private LocalDateTime flightDeparture;

    @Column(name = "last_name", length = 200)
    private String lastName;

    @Column(name = "email", length = 300)
    private String email;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 200)
    private String lastRunStatus;

    @Column(name = "run_count")
    private int runCount = 0;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }

    public String getPortal() { return portal; }
    public void setPortal(String portal) { this.portal = portal; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getScheduleDescription() { return scheduleDescription; }
    public void setScheduleDescription(String scheduleDescription) { this.scheduleDescription = scheduleDescription; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }

    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }

    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public LocalDateTime getTriggerAt() { return triggerAt; }
    public void setTriggerAt(LocalDateTime triggerAt) { this.triggerAt = triggerAt; }

    public LocalDateTime getFlightDeparture() { return flightDeparture; }
    public void setFlightDeparture(LocalDateTime flightDeparture) { this.flightDeparture = flightDeparture; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
