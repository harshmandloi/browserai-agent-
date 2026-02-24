package browserAI.demo.fsm;

/**
 * Finite State Machine states for the conversation/agent execution flow.
 *
 * State transitions:
 *   INIT -> NEED_PORTAL (if portal missing)
 *   INIT -> NEED_CREDENTIAL (if no creds stored)
 *   INIT -> NEED_REFERENCE (if reference needed but missing)
 *   INIT -> EXECUTION_READY (all info available)
 *   NEED_PORTAL -> INIT (user provides portal)
 *   NEED_CREDENTIAL -> INIT (user stores creds)
 *   NEED_REFERENCE -> EXECUTION_READY (user provides ref)
 *   EXECUTION_READY -> EXECUTION_RUNNING
 *   EXECUTION_RUNNING -> CAPTCHA_DETECTED (portal shows captcha)
 *   EXECUTION_RUNNING -> OTP_REQUIRED (portal needs OTP)
 *   EXECUTION_RUNNING -> SUCCESS
 *   EXECUTION_RUNNING -> FAILURE
 *   CAPTCHA_DETECTED -> EXECUTION_RUNNING (captcha solved, retry)
 *   CAPTCHA_DETECTED -> FAILURE (captcha unsolvable)
 *   OTP_REQUIRED -> EXECUTION_RUNNING (OTP provided)
 *   OTP_REQUIRED -> FAILURE (OTP timeout)
 */
public enum ConversationState {

    INIT("Conversation initialized, parsing intent"),
    NEED_PORTAL("Portal name could not be determined, asking user"),
    NEED_CREDENTIAL("Credentials not found for portal, asking user to store"),
    NEED_REFERENCE("Document reference/ID is needed but missing"),
    EXECUTION_READY("All info gathered, ready to execute"),
    EXECUTION_RUNNING("Browser automation in progress"),
    CAPTCHA_DETECTED("CAPTCHA detected on portal, needs resolution"),
    OTP_REQUIRED("OTP/2FA required by portal"),
    SUCCESS("Document downloaded and processed successfully"),
    FAILURE("Execution failed");

    private final String description;

    ConversationState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILURE;
    }

    public boolean needsUserInput() {
        return this == NEED_PORTAL || this == NEED_CREDENTIAL
            || this == NEED_REFERENCE || this == OTP_REQUIRED;
    }
}
