package browserAI.demo.service;

import browserAI.demo.model.dto.AgentRequest;
import browserAI.demo.model.dto.CheckInRequest;
import browserAI.demo.model.entity.ScheduledTask;
import browserAI.demo.repository.ScheduledTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages scheduled tasks — recurring (cron) and one-time (auto check-in).
 * Uses Spring TaskScheduler to run jobs that trigger the AgentOrchestrator.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final ScheduledTaskRepository taskRepository;
    private final GeminiService geminiService;
    private final CredentialVaultService credentialVaultService;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;

    private AgentOrchestrator orchestrator;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public SchedulerService(ScheduledTaskRepository taskRepository,
                            GeminiService geminiService,
                            CredentialVaultService credentialVaultService,
                            TaskScheduler taskScheduler,
                            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.geminiService = geminiService;
        this.credentialVaultService = credentialVaultService;
        this.taskScheduler = taskScheduler;
        this.objectMapper = objectMapper;
    }

    /**
     * Lazy setter to break circular dependency with AgentOrchestrator.
     */
    public void setOrchestrator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    public void reloadActiveSchedules() {
        List<ScheduledTask> activeTasks = taskRepository.findByStatus("ACTIVE");
        log.info("[Scheduler] Reloading {} active scheduled tasks", activeTasks.size());
        for (ScheduledTask task : activeTasks) {
            if ("CHECKIN".equals(task.getTaskType()) && task.getTriggerAt() != null) {
                if (task.getTriggerAt().isAfter(LocalDateTime.now())) {
                    scheduleOneTimeExecution(task);
                } else {
                    log.info("[Scheduler] Check-in task {} trigger time has passed, marking EXPIRED", task.getId());
                    task.setStatus("EXPIRED");
                    taskRepository.save(task);
                }
            } else if (task.getCronExpression() != null) {
                scheduleExecution(task);
            }
        }
    }

    /**
     * Creates a new scheduled task from natural language schedule description.
     * Uses Gemini to convert "every month on 5th" → cron expression.
     */
    public ScheduledTask createSchedule(String userId, String requestId, String inputText,
                                         String portal, String documentType, String reference,
                                         String scheduleDescription) {
        String cronJson = geminiService.scheduleToCron(scheduleDescription);
        if (cronJson == null) {
            throw new RuntimeException("Could not convert schedule to cron expression: " + scheduleDescription);
        }

        String cronExpression;
        String humanDescription;
        try {
            JsonNode parsed = objectMapper.readTree(cronJson);
            cronExpression = parsed.path("cron").asText();
            humanDescription = parsed.path("description").asText(scheduleDescription);
        } catch (Exception e) {
            log.error("[Scheduler] Failed to parse cron JSON: {}", cronJson);
            throw new RuntimeException("Invalid cron conversion result");
        }

        // Validate cron expression
        try {
            new CronTrigger(cronExpression);
        } catch (Exception e) {
            throw new RuntimeException("Invalid cron expression generated: " + cronExpression);
        }

        ScheduledTask task = new ScheduledTask();
        task.setUserId(userId);
        task.setRequestId(requestId);
        task.setInputText(inputText);
        task.setPortal(portal);
        task.setDocumentType(documentType);
        task.setReference(reference);
        task.setCronExpression(cronExpression);
        task.setScheduleDescription(humanDescription);
        task.setStatus("ACTIVE");

        ScheduledTask saved = taskRepository.save(task);
        log.info("[Scheduler] Created schedule: id={}, cron='{}', desc='{}'",
                saved.getId(), cronExpression, humanDescription);

        scheduleExecution(saved);
        return saved;
    }

    /**
     * Schedules auto web check-in — one-time trigger at (departureTime - hoursBeforeDeparture).
     * Works for ANY airline dynamically. The agent will navigate the check-in page at trigger time.
     */
    public ScheduledTask scheduleCheckIn(CheckInRequest request, String requestId) {
        LocalDateTime departure = request.getDepartureDateTime();
        int hoursBefore = request.getHoursBeforeDeparture() != null ? request.getHoursBeforeDeparture() : 24;
        LocalDateTime triggerAt = departure.minusHours(hoursBefore);

        if (triggerAt.isBefore(LocalDateTime.now())) {
            throw new RuntimeException(
                    "Check-in trigger time (%s) is in the past. Flight departs at %s, trigger is %dh before."
                            .formatted(triggerAt, departure, hoursBefore));
        }

        String airline = request.getAirline().toLowerCase().trim();
        String inputText = "Perform web check-in on %s for PNR %s, last name %s and download boarding pass"
                .formatted(airline, request.getPnr(), request.getLastName());
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            inputText += " and email is " + request.getEmail();
        }

        ScheduledTask task = new ScheduledTask();
        task.setUserId(request.getUserId());
        task.setRequestId(requestId);
        task.setInputText(inputText);
        task.setPortal(airline);
        task.setDocumentType("boardingpass");
        task.setReference(request.getPnr());
        task.setTaskType("CHECKIN");
        task.setTriggerAt(triggerAt);
        task.setFlightDeparture(departure);
        task.setLastName(request.getLastName());
        task.setEmail(request.getEmail());
        task.setNextRunAt(triggerAt);
        task.setScheduleDescription("Auto web check-in %dh before departure (%s) — %s PNR %s"
                .formatted(hoursBefore, departure, airline.toUpperCase(), request.getPnr()));
        task.setStatus("ACTIVE");

        ScheduledTask saved = taskRepository.save(task);
        log.info("[Scheduler] Check-in scheduled: id={}, airline={}, PNR={}, triggerAt={}, departure={}",
                saved.getId(), airline, request.getPnr(), triggerAt, departure);

        // Store lastName + email as credentials so the agent can use them at check-in time
        credentialVaultService.storeCredential(
                request.getUserId(), airline,
                request.getLastName(),
                request.getEmail() != null ? request.getEmail() : request.getLastName());
        log.info("[Scheduler] Credentials stored for check-in: userId={}, airline={}", request.getUserId(), airline);

        scheduleOneTimeExecution(saved);
        return saved;
    }

    /**
     * Registers a ONE-TIME task execution at a specific datetime.
     * Used for auto web check-in before flights.
     */
    private void scheduleOneTimeExecution(ScheduledTask task) {
        try {
            Instant triggerInstant = task.getTriggerAt().atZone(ZoneId.systemDefault()).toInstant();

            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                log.info("[Scheduler] ===== ONE-TIME TRIGGER: check-in task {} firing at {} =====",
                        task.getId(), LocalDateTime.now());
                executeScheduledTask(task);
                task.setStatus("COMPLETED");
                taskRepository.save(task);
            }, triggerInstant);

            activeFutures.put(task.getId(), future);
            log.info("[Scheduler] Registered one-time check-in task {} — triggers at {} (in {}h {}m)",
                    task.getId(), task.getTriggerAt(),
                    java.time.Duration.between(LocalDateTime.now(), task.getTriggerAt()).toHours(),
                    java.time.Duration.between(LocalDateTime.now(), task.getTriggerAt()).toMinutesPart());

        } catch (Exception e) {
            log.error("[Scheduler] Failed to register one-time task {}: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * Registers a RECURRING task with cron trigger.
     */
    private void scheduleExecution(ScheduledTask task) {
        try {
            CronTrigger trigger = new CronTrigger(task.getCronExpression());

            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                executeScheduledTask(task);
            }, trigger);

            activeFutures.put(task.getId(), future);
            log.info("[Scheduler] Registered task {} with cron '{}'", task.getId(), task.getCronExpression());

        } catch (Exception e) {
            log.error("[Scheduler] Failed to register task {}: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * Executes a scheduled task by calling the orchestrator.
     */
    private void executeScheduledTask(ScheduledTask task) {
        log.info("[Scheduler] ===== EXECUTING scheduled task {} =====", task.getId());
        log.info("[Scheduler] userId={}, portal={}, type={}", task.getUserId(), task.getPortal(), task.getDocumentType());

        try {
            if (orchestrator == null) {
                log.error("[Scheduler] Orchestrator not set — cannot execute task");
                return;
            }

            AgentRequest request = new AgentRequest(task.getUserId(), task.getInputText());
            orchestrator.execute(request);

            task.setLastRunAt(LocalDateTime.now());
            task.setLastRunStatus("SUCCESS");
            task.setRunCount(task.getRunCount() + 1);
            taskRepository.save(task);
            log.info("[Scheduler] Task {} completed successfully (run #{})", task.getId(), task.getRunCount());

        } catch (Exception e) {
            log.error("[Scheduler] Task {} failed: {}", task.getId(), e.getMessage());
            task.setLastRunAt(LocalDateTime.now());
            task.setLastRunStatus("FAILED: " + e.getMessage().substring(0, Math.min(200, e.getMessage().length())));
            task.setRunCount(task.getRunCount() + 1);
            taskRepository.save(task);
        }
    }

    /**
     * Cancels/pauses a scheduled task.
     */
    public ScheduledTask cancelSchedule(Long taskId) {
        ScheduledTask task = taskRepository.findById(taskId).orElseThrow(
                () -> new RuntimeException("Scheduled task not found: " + taskId));

        ScheduledFuture<?> future = activeFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }

        task.setStatus("CANCELLED");
        return taskRepository.save(task);
    }

    public List<ScheduledTask> getUserSchedules(String userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<ScheduledTask> getActiveSchedules() {
        return taskRepository.findByStatus("ACTIVE");
    }
}
