package tax.usecases.boundary;

import lombok.Builder;

import tax.domain.model.ErrorCode;

import java.util.Objects;
import java.util.UUID;

@Builder
public record DomainError(
        ErrorCode code,
        String detail,
        UUID correlationId
) {
    public DomainError {
        Objects.requireNonNull(code, "code must not be null");
        if (detail == null || detail.isBlank())
            throw new IllegalArgumentException("detail must not be blank");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }
}
