package tax.usecases.gateway;

import lombok.Builder;

import tax.domain.model.ClassificationCode;

import java.time.LocalDate;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Input to the rule-resolution gateway (SPEC-TAX-002 §6).
 */
@Builder
public record RuleResolutionRequest(UUID tenantId,
                                     ClassificationCode classificationCode,
                                     LocalDate operationDate) {
    public RuleResolutionRequest {
        requireNonNull(tenantId,           "tenantId must not be null");
        requireNonNull(classificationCode, "classificationCode must not be null");
        requireNonNull(operationDate,      "operationDate must not be null");
    }
}
