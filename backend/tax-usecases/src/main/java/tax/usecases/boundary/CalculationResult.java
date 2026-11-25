package tax.usecases.boundary;

import lombok.Builder;

import tax.domain.model.CalculationStatus;
import tax.domain.model.Currency;
import tax.domain.model.Money;
import tax.domain.model.SimulationContext;
import tax.domain.model.TaxComponent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Builder
public record CalculationResult(
        UUID calculationId,
        CalculationStatus status,
        Currency currency,
        List<TaxComponent> components,
        Money totalTax,
        UUID ruleVersionId,
        String engineVersion,
        SimulationContext simulationContext,
        String inputCanonicalHash
) {
    public CalculationResult {
        Objects.requireNonNull(calculationId, "calculationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (components == null || components.isEmpty())
            throw new IllegalArgumentException("components must not be null or empty");
        components = List.copyOf(components);
        Objects.requireNonNull(totalTax, "totalTax must not be null");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId must not be null");
        if (engineVersion == null || engineVersion.isBlank())
            throw new IllegalArgumentException("engineVersion must not be blank");
        Objects.requireNonNull(simulationContext, "simulationContext must not be null");
        if (inputCanonicalHash == null || !inputCanonicalHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(
                    "inputCanonicalHash must be 64 lowercase hex characters");
    }
}
