package browserAI.demo.controller;

import browserAI.demo.model.dto.AgentRequest;
import browserAI.demo.model.dto.AgentResponse;
import browserAI.demo.model.dto.CaptchaRequest;
import browserAI.demo.model.dto.CheckInRequest;
import browserAI.demo.model.dto.CredentialRequest;
import browserAI.demo.model.dto.OtpRequest;
import browserAI.demo.model.entity.AuditLog;
import browserAI.demo.model.entity.Document;
import browserAI.demo.model.entity.ScheduledTask;
import browserAI.demo.repository.DocumentRepository;
import browserAI.demo.service.AgentOrchestrator;
import browserAI.demo.service.AuditService;
import browserAI.demo.service.CaptchaSolverService;
import browserAI.demo.service.CredentialVaultService;
import browserAI.demo.service.OtpService;
import browserAI.demo.service.RateLimiterService;
import browserAI.demo.service.RequestTrackingService;
import browserAI.demo.service.SchedulerService;
import browserAI.demo.util.LogMaskingUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * REST API Controller for the AI Browser Agent.
 *
 * Endpoints:
 *   POST /api/request           — Main agent endpoint (natural language input)
 *   POST /api/credentials       — Store portal credentials (encrypted)
 *   POST /api/otp               — Submit OTP for pending browser session
 *   POST /api/captcha           — Submit CAPTCHA answer for pending browser session
 *   GET  /api/captcha/{userId}  — Get CAPTCHA image (base64) for manual solving
 *   GET  /api/audit/{sessionId} — Get execution trace for a session
 *   GET  /api/health            — Health check
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final CredentialVaultService credentialVaultService;
    private final AuditService auditService;
    private final RateLimiterService rateLimiterService;
    private final OtpService otpService;
    private final CaptchaSolverService captchaSolverService;
    private final DocumentRepository documentRepository;
    private final browserAI.demo.service.TokenTrackingService tokenTrackingService;
    private final SchedulerService schedulerService;
    private final RequestTrackingService requestTrackingService;

    public AgentController(AgentOrchestrator orchestrator,
                           CredentialVaultService credentialVaultService,
                           AuditService auditService,
                           RateLimiterService rateLimiterService,
                           OtpService otpService,
                           CaptchaSolverService captchaSolverService,
                           DocumentRepository documentRepository,
                           browserAI.demo.service.TokenTrackingService tokenTrackingService,
                           SchedulerService schedulerService,
                           RequestTrackingService requestTrackingService) {
        this.orchestrator = orchestrator;
        this.credentialVaultService = credentialVaultService;
        this.auditService = auditService;
        this.rateLimiterService = rateLimiterService;
        this.otpService = otpService;
        this.captchaSolverService = captchaSolverService;
        this.documentRepository = documentRepository;
        this.tokenTrackingService = tokenTrackingService;
        this.schedulerService = schedulerService;
        this.requestTrackingService = requestTrackingService;
    }

    /**
     * Main agent endpoint.
     * Accepts natural language input, returns downloaded document metadata.
     *
     * Example:
     * POST /api/request
     * {
     *   "userId": "1",
     *   "input": "Download invoice ABC123 from demo"
     * }
     */
    @PostMapping("/request")
    public ResponseEntity<AgentResponse> processRequest(@Valid @RequestBody AgentRequest request) {
        log.info("Received agent request: userId={}, input='{}'",
                request.getUserId(), LogMaskingUtil.mask(request.getInput()));

        AgentResponse response = orchestrator.execute(request);

        if (response.getRequiredFields() != null && !response.getRequiredFields().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Store portal credentials (encrypted with AES-256-GCM).
     *
     * Example:
     * POST /api/credentials
     * {
     *   "userId": "1",
     *   "portal": "demo",
     *   "username": "user@example.com",
     *   "password": "secretpass"
     * }
     */
    @PostMapping("/credentials")
    public ResponseEntity<AgentResponse> storeCredentials(@Valid @RequestBody CredentialRequest request) {
        log.info("Storing credentials: userId={}, portal={}, username={}",
                request.getUserId(), request.getPortal(),
                LogMaskingUtil.maskUsername(request.getUsername()));

        credentialVaultService.storeCredential(
                request.getUserId(),
                request.getPortal(),
                request.getUsername(),
                request.getPassword()
        );

        return ResponseEntity.ok(AgentResponse.success(
                "Credentials stored securely for portal: " + request.getPortal(), null));
    }

    /**
     * Get execution audit trail for a session.
     * Useful for debugging and compliance.
     */
    @GetMapping("/audit/{sessionId}")
    public ResponseEntity<List<AuditLog>> getAuditTrail(@PathVariable String sessionId) {
        log.info("Fetching audit trail for session: {}", sessionId);
        List<AuditLog> trail = auditService.getSessionTrace(sessionId);
        return ResponseEntity.ok(trail);
    }

    /**
     * Submit OTP for a pending browser automation session.
     *
     * When the agent detects an OTP page during automation, it waits for the user
     * to submit the OTP via this endpoint. The browser session polls Redis until
     * the OTP arrives (timeout: 2 minutes).
     *
     * Example:
     * POST /api/otp
     * {
     *   "userId": "1",
     *   "otp": "123456"
     * }
     */
    @PostMapping("/otp")
    public ResponseEntity<AgentResponse> submitOtp(@Valid @RequestBody OtpRequest request) {
        log.info("OTP submitted for userId={}", request.getUserId());
        otpService.submitOtp(request.getUserId(), request.getOtp());
        return ResponseEntity.ok(AgentResponse.success(
                "OTP submitted successfully. Browser session will pick it up shortly.", null));
    }

    /**
     * Submit CAPTCHA answer for a pending browser automation session.
     *
     * When the agent can't solve a CAPTCHA via AI (Gemini Vision), it stores
     * the CAPTCHA image in Redis and waits for the user to submit the answer.
     *
     * Example:
     * POST /api/captcha
     * {
     *   "userId": "1",
     *   "answer": "A8X2K"
     * }
     */
    @PostMapping("/captcha")
    public ResponseEntity<AgentResponse> submitCaptchaAnswer(@Valid @RequestBody CaptchaRequest request) {
        log.info("CAPTCHA answer submitted for userId={}", request.getUserId());
        captchaSolverService.submitCaptchaAnswer(request.getUserId(), request.getAnswer());
        return ResponseEntity.ok(AgentResponse.success(
                "CAPTCHA answer submitted. Browser session will pick it up shortly.", null));
    }

    /**
     * Get the CAPTCHA image for manual solving.
     * Returns the image as base64 PNG (in JSON) or as raw image bytes.
     *
     * Usage:
     *   GET /api/captcha/1          → JSON with base64 string
     *   GET /api/captcha/1/image    → Raw PNG image (for display in browser/app)
     */
    @GetMapping("/captcha/{userId}")
    public ResponseEntity<?> getCaptchaImage(@PathVariable String userId) {
        String base64Image = captchaSolverService.getCaptchaImage(userId);
        if (base64Image == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "image", base64Image,
                "message", "Solve this CAPTCHA and POST your answer to /api/captcha"
        ));
    }

    @GetMapping(value = "/captcha/{userId}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getCaptchaRawImage(@PathVariable String userId) {
        String base64Image = captchaSolverService.getCaptchaImage(userId);
        if (base64Image == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(imageBytes);
    }

    /**
     * Download a stored document by ID.
     * Returns the actual file (PDF/HTML) for direct download in Postman or browser.
     *
     * GET /api/download/1
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
                                                      @RequestParam(required = false) String userId) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }

        // Authorization: verify the requesting user owns this document
        if (userId != null && !userId.isBlank() && !doc.getUserId().equals(userId)) {
            log.warn("Unauthorized download attempt: userId={} tried to download doc owned by {}",
                    userId, doc.getUserId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = new File(doc.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        // Dynamic content type detection
        String fileName = doc.getFileName().toLowerCase();
        String contentType;
        if (fileName.endsWith(".pdf")) contentType = "application/pdf";
        else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) contentType = "image/jpeg";
        else if (fileName.endsWith(".png")) contentType = "image/png";
        else if (fileName.endsWith(".webp")) contentType = "image/webp";
        else if (fileName.endsWith(".html")) contentType = "text/html";
        else if (fileName.endsWith(".docx")) contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        else if (fileName.endsWith(".xlsx")) contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        else contentType = "application/octet-stream";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getFileName() + "\"")
                .body(new FileSystemResource(file));
    }

    /**
     * Get token usage — global, per user, or per session.
     *
     * GET /api/tokens                  → Global token usage stats
     * GET /api/tokens?userId=test1     → Token usage for a specific user
     * GET /api/tokens?sessionId=abc123 → Token usage for a specific session
     */
    @GetMapping("/tokens")
    public ResponseEntity<Map<String, Object>> getTokenUsage(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId) {

        if (sessionId != null && !sessionId.isBlank()) {
            return ResponseEntity.ok(tokenTrackingService.getSessionUsage(sessionId));
        }
        if (userId != null && !userId.isBlank()) {
            return ResponseEntity.ok(tokenTrackingService.getUserUsage(userId));
        }
        return ResponseEntity.ok(tokenTrackingService.getGlobalUsage());
    }

    /**
     * Schedule auto web check-in for ANY airline.
     * Triggers automatically N hours before departure (default: 24h).
     * Agent dynamically navigates the airline's check-in page, fills PNR + last name,
     * completes check-in, and downloads the boarding pass.
     *
     * POST /api/check-in/schedule
     * {
     *   "userId": "user1",
     *   "airline": "indigo",
     *   "pnr": "ABC123",
     *   "lastName": "Doe",
     *   "email": "user@example.com",
     *   "departureDateTime": "2026-03-15T14:00:00",
     *   "hoursBeforeDeparture": 24
     * }
     */
    @PostMapping("/check-in/schedule")
    public ResponseEntity<AgentResponse> scheduleCheckIn(@Valid @RequestBody CheckInRequest request) {
        log.info("Check-in schedule request: userId={}, airline={}, PNR={}, departure={}",
                request.getUserId(), request.getAirline(), request.getPnr(), request.getDepartureDateTime());

        try {
            var requestLog = requestTrackingService.createRequestLog(
                    request.getUserId(),
                    "Auto check-in: %s PNR %s".formatted(request.getAirline(), request.getPnr()),
                    "checkin", request.getAirline(), "boardingpass", request.getPnr());
            String requestId = requestLog.getRequestId();

            ScheduledTask task = schedulerService.scheduleCheckIn(request, requestId);

            requestTrackingService.completeRequest(requestId, "SCHEDULED",
                    task.getScheduleDescription(), null, 0L, null, null);

            AgentResponse.ScheduleInfo info = new AgentResponse.ScheduleInfo();
            info.setScheduleId(task.getId());
            info.setDescription(task.getScheduleDescription());
            info.setStatus("ACTIVE");
            info.setNextRunAt(task.getTriggerAt().toString());

            AgentResponse resp = AgentResponse.scheduleCreated(
                    "Auto web check-in scheduled! Agent will check in at " + task.getTriggerAt() +
                    " (" + request.getHoursBeforeDeparture() + "h before departure). " +
                    "Boarding pass will be downloaded to your system automatically.", info);
            resp.setRequestId(requestId);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Failed to schedule check-in: {}", e.getMessage());
            return ResponseEntity.badRequest().body(AgentResponse.error(e.getMessage()));
        }
    }

    /**
     * Get all scheduled check-ins for a user.
     * GET /api/check-in/list?userId=user1
     */
    @GetMapping("/check-in/list")
    public ResponseEntity<List<ScheduledTask>> getCheckIns(@RequestParam String userId) {
        return ResponseEntity.ok(schedulerService.getUserSchedules(userId));
    }

    /**
     * Cancel a scheduled check-in.
     * DELETE /api/check-in/{taskId}
     */
    @DeleteMapping("/check-in/{taskId}")
    public ResponseEntity<AgentResponse> cancelCheckIn(@PathVariable Long taskId) {
        try {
            ScheduledTask cancelled = schedulerService.cancelSchedule(taskId);
            return ResponseEntity.ok(AgentResponse.success(
                    "Check-in cancelled for " + cancelled.getPortal() + " PNR " + cancelled.getReference(), null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(AgentResponse.error(e.getMessage()));
        }
    }

    /**
     * Health check endpoint with rate limit info.
     */
    @GetMapping("/health")
    public ResponseEntity<AgentResponse> health() {
        return ResponseEntity.ok(AgentResponse.success("AI Browser Agent is running", null));
    }
}
