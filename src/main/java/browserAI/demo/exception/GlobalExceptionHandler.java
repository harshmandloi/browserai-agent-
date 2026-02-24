package browserAI.demo.exception;

import browserAI.demo.model.dto.AgentResponse;
import browserAI.demo.service.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler — returns clean JSON responses for all error scenarios.
 * Never exposes stack traces in production.
 * Handles all custom exceptions + governance violations.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<AgentResponse> handleGeminiException(GeminiException ex) {
        log.error("Gemini error: {}", ex.getMessage());
        boolean isRateLimit = ex.getMessage() != null && ex.getMessage().contains("429");
        if (isRateLimit) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(AgentResponse.error("AI rate limit reached. Please wait 1-2 minutes and try again."));
        }
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(AgentResponse.error("AI processing failed. Please try again."));
    }

    @ExceptionHandler(PortalNotSupportedException.class)
    public ResponseEntity<AgentResponse> handlePortalNotSupported(PortalNotSupportedException ex) {
        log.warn("Portal not supported: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MissingDataException.class)
    public ResponseEntity<AgentResponse> handleMissingData(MissingDataException ex) {
        log.warn("Agent needs data from user: {} — fields: {}", ex.getMessage(), ex.getMissingFields());
        AgentResponse response = AgentResponse.needsInput(null, ex.getMessage(), ex.getMissingFields());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(response);
    }

    @ExceptionHandler(LoginFailureException.class)
    public ResponseEntity<AgentResponse> handleLoginFailure(LoginFailureException ex) {
        log.error("Login failure: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(AgentResponse.error("Portal login failed: " + ex.getMessage()));
    }

    @ExceptionHandler(DownloadTimeoutException.class)
    public ResponseEntity<AgentResponse> handleDownloadTimeout(DownloadTimeoutException ex) {
        log.error("Download timeout: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(AgentResponse.error("Download timed out: " + ex.getMessage()));
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<AgentResponse> handleDuplicateRequest(DuplicateRequestException ex) {
        log.warn("Duplicate request: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(AgentResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(GovernanceService.GovernanceViolationException.class)
    public ResponseEntity<AgentResponse> handleGovernanceViolation(GovernanceService.GovernanceViolationException ex) {
        log.error("Governance violation: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(AgentResponse.error("Governance limit exceeded: " + ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.error(message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentResponse> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state transition: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(AgentResponse.error("Invalid operation: " + ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(AgentResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AgentResponse.error("Internal server error. Please try again later."));
    }
}
