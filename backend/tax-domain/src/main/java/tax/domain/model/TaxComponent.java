package tax.domain.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Objects;

@Builder
public record TaxComponent(
        TaxType taxType,
        BigDecimal taxableBase,
        BigDecimal rate,
        Money amount
) {
    public TaxComponent {
        Objects.requireNonNull(taxType, "taxType must not be null");
        Objects.requireNonNull(taxableBase, "taxableBase must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
