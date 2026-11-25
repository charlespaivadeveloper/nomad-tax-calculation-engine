package tax.domain.calculation;

import lombok.Builder;
import tax.domain.model.Money;
import tax.domain.model.TaxComponent;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Immutable result of a single tax calculation (SPEC-TAX-003 §3).
 * Components are ordered CBS before IBS per TaxType.ordinal().
 */
@Builder
public record CalculationComponents(List<TaxComponent> components, Money totalTax) {

    public CalculationComponents {
        requireNonNull(components, "components must not be null");
        if (components.isEmpty())
            throw new IllegalArgumentException("components must not be empty");
        components = List.copyOf(components);
        requireNonNull(totalTax, "totalTax must not be null");
    }
}
