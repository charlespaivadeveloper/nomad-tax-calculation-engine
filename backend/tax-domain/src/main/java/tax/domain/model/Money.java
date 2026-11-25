package tax.domain.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Builder
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(String amountStr, Currency currency) {
        Objects.requireNonNull(amountStr, "amountStr must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid amount: " + amountStr, e);
        }
        if (parsed.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("amount must not be negative: " + amountStr);
        return new Money(parsed.setScale(2, RoundingMode.HALF_EVEN), currency);
    }

    public Money add(Money other) {
        if (this.currency != other.currency)
            throw new IllegalArgumentException(
                    "currency mismatch: " + this.currency + " vs " + other.currency);
        return new Money(
                this.amount.add(other.amount).setScale(2, RoundingMode.HALF_EVEN),
                this.currency);
    }
}
