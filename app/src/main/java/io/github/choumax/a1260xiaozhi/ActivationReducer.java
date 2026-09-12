package io.github.choumax.a1260xiaozhi;

/** Android-free state table for normal OTA activation v1. */
public final class ActivationReducer {
    public enum Event { START, OTA_BINDING, OTA_CONFIG, ACTIVATE_202, ACTIVATE_200, CHECK_ERROR, CANCEL }
    private ActivationReducer() { }
    public static ActivationState next(ActivationState state, Event event) {
        switch (event) {
            case START: return ActivationState.CHECKING_OTA;
            case OTA_BINDING: return ActivationState.WAITING_USER_BIND;
            case OTA_CONFIG: return ActivationState.CONFIG_READY;
            case ACTIVATE_202: return state == ActivationState.WAITING_USER_BIND || state == ActivationState.POLLING_ACTIVATE ? ActivationState.POLLING_ACTIVATE : state;
            case ACTIVATE_200: return ActivationState.RECHECKING_OTA;
            case CHECK_ERROR: return ActivationState.ERROR;
            case CANCEL: return ActivationState.CANCELLED;
            default: return state;
        }
    }
}
