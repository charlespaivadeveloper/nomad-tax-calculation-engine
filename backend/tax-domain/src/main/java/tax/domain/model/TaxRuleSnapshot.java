package tax.domain.model;

import lombok.Builder;

import java.util.Objects;
import java.util.UUID;

@Builder
public record TaxRuleSnapshot(
        UUID id,
        TaxType taxType,
        ClassificationCode classificationCode,
        TaxPeriod period,
        TaxRate rateDecimal,
        String snapshotHash,
        RuleStatus status,
        String engineVersion
) {
    public TaxRuleSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taxType, "taxType must not be null");
        Objects.requireNonNull(classificationCode, "classificationCode must not be null");
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(rateDecimal, "rateDecimal must not be null");
        if (snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(
                    "snapshotHash must be exactly 64 lowercase hex characters");
        Objects.requireNonNull(status, "status must not be null");
        if (engineVersion == null || engineVersion.isBlank())
            throw new IllegalArgumentException("engineVersion must not be blank");
    }
}
