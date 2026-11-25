package tax.usecases.boundary;

import lombok.Builder;

import tax.domain.model.ClassificationCode;
import tax.domain.model.Currency;
import tax.domain.model.OperationId;
import tax.domain.model.SimulationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Builder
public record TaxOperationInput(
        UUID tenantId,
        OperationId operationId,
        String idempotencyKey,
        LocalDate operationDate,
        Currency currency,
        ClassificationCode classificationCode,
        BigDecimal taxableBase,
        SimulationContext simulationContext
) {
    public TaxOperationInput {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new IllegalArgumentException("idempotencyKey must be 1-128 characters");
        Objects.requireNonNull(operationDate, "operationDate must not be null");
        if (operationDate.isAfter(LocalDate.now()))
            throw new IllegalArgumentException(
                    "operationDate " + operationDate + " must not be in the future");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(classificationCode, "classificationCode must not be null");
        Objects.requireNonNull(taxableBase, "taxableBase must not be null");
        if (taxableBase.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("taxableBase must be positive");
        if (taxableBase.scale() > 4)
            throw new IllegalArgumentException(
                    "taxableBase scale " + taxableBase.scale() + " exceeds maximum 4");
        Objects.requireNonNull(simulationContext, "simulationContext must not be null");
    }
}
