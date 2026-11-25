package tax.domain.model;

import lombok.Builder;

import java.time.LocalDate;
import java.util.Objects;

@Builder
public record TaxPeriod(LocalDate validFrom, LocalDate validTo) {

    public TaxPeriod {
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (validTo != null && !validTo.isAfter(validFrom))
            throw new IllegalArgumentException(
                    "validTo must be strictly after validFrom; got " + validFrom + " and " + validTo);
    }

    /** Half-open semantics: [validFrom, validTo). validTo == null means open-ended. */
    public boolean covers(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo));
    }
}
