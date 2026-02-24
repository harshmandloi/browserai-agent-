package browserAI.demo.fsm;

import java.util.Map;
import java.util.Set;

/**
 * Defines valid state transitions for the FSM.
 * Prevents illegal state jumps.
 */
public final class StateTransition {

    private static final Map<ConversationState, Set<ConversationState>> VALID_TRANSITIONS = Map.ofEntries(
        Map.entry(ConversationState.INIT, Set.of(
            ConversationState.NEED_PORTAL,
            ConversationState.NEED_CREDENTIAL,
            ConversationState.NEED_REFERENCE,
            ConversationState.EXECUTION_READY,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.NEED_PORTAL, Set.of(
            ConversationState.INIT,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.NEED_CREDENTIAL, Set.of(
            ConversationState.INIT,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.NEED_REFERENCE, Set.of(
            ConversationState.EXECUTION_READY,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.EXECUTION_READY, Set.of(
            ConversationState.EXECUTION_RUNNING,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.EXECUTION_RUNNING, Set.of(
            ConversationState.CAPTCHA_DETECTED,
            ConversationState.OTP_REQUIRED,
            ConversationState.NEED_CREDENTIAL,
            ConversationState.SUCCESS,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.CAPTCHA_DETECTED, Set.of(
            ConversationState.EXECUTION_RUNNING,
            ConversationState.FAILURE
        )),
        Map.entry(ConversationState.OTP_REQUIRED, Set.of(
            ConversationState.EXECUTION_RUNNING,
            ConversationState.FAILURE
        ))
    );

    private StateTransition() {}

    public static boolean isValid(ConversationState from, ConversationState to) {
        Set<ConversationState> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void validate(ConversationState from, ConversationState to) {
        if (!isValid(from, to)) {
            throw new IllegalStateException(
                "Invalid state transition: %s -> %s".formatted(from, to));
        }
    }
}
