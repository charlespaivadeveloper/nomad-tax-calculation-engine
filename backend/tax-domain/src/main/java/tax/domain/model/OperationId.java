package tax.domain.model;

import lombok.Builder;

import java.util.Objects;

@Builder
public record OperationId(String value) {

    public OperationId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 255)
            throw new IllegalArgumentException(
                    "operationId must be 1-255 characters");
    }
}
