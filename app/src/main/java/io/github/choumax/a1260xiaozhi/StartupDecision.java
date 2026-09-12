package io.github.choumax.a1260xiaozhi;

/** Pure startup policy kept testable outside Activity lifecycle code. */
final class StartupDecision {
    enum Action { ACTIVATE, CONNECT }
    private StartupDecision() { }
    static Action decide(boolean customServer, boolean hasIssuedConfig) {
        return !customServer && !hasIssuedConfig ? Action.ACTIVATE : Action.CONNECT;
    }
}
