package tax.domain.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Builder
public record TaxRate(BigDecimal value) {

    private static final BigDecimal MIN = new BigDecimal("0.000000001");
    private static final BigDecimal MAX = new BigDecimal("0.999999999");

    public TaxRate {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static TaxRate of(String rateStr) {
        Objects.requireNonNull(rateStr, "rateStr must not be null");
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(rateStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid rate: " + rateStr, e);
        }
        var scaled = parsed.setScale(9, RoundingMode.HALF_EVEN);
        if (scaled.compareTo(MIN) < 0 || scaled.compareTo(MAX) > 0)
            throw new IllegalArgumentException(
                    "rate out of range [0.000000001, 0.999999999]: " + rateStr);
        return new TaxRate(scaled);
    }
}
