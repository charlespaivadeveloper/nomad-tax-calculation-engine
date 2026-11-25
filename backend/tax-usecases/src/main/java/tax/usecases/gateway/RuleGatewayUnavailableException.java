package tax.usecases.gateway;

/**
 * Checked exception thrown when the rule store is unreachable (SPEC-TAX-002 §7.3).
 * Maps to ErrorCode.DEPENDENCY_UNAVAILABLE at the interactor boundary.
 */
public final class RuleGatewayUnavailableException extends Exception {

    public RuleGatewayUnavailableException() {
        super("rule store unavailable");
    }

    public RuleGatewayUnavailableException(Throwable cause) {
        super("rule store unavailable", cause);
    }
}
