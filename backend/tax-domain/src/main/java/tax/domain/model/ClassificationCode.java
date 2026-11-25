package tax.domain.model;

import lombok.Builder;

import java.util.Objects;

@Builder
public record ClassificationCode(String value) {

    public ClassificationCode {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 64)
            throw new IllegalArgumentException(
                    "classificationCode must be 1-64 characters: " + value);
        if (!value.matches("[A-Z0-9_]+"))
            throw new IllegalArgumentException(
                    "classificationCode fails pattern [A-Z0-9_]+: " + value);
    }
}
