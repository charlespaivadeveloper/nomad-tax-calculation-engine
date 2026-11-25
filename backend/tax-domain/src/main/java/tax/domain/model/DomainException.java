package tax.domain.model;

import static java.util.Objects.requireNonNull;

/**
 * Unchecked exception thrown by domain services for precondition violations (SPEC-TAX-003 §3).
 * Not to be confused with {@link DomainError}, which is a returned value object.
 */
public final class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;

    public DomainException(ErrorCode code, String detail) {
        super(detail);
        this.code   = requireNonNull(code,   "code must not be null");
        this.detail = requireNonNull(detail, "detail must not be null");
    }

    public ErrorCode code()   { return code; }
    public String    detail() { return detail; }
}
