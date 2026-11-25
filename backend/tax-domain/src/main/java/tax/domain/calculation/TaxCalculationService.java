package tax.domain.calculation;

import tax.domain.model.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure deterministic tax calculation service (SPEC-TAX-003 §2 and §4).
 * Stateless; safe for concurrent use without synchronisation.
 * No I/O, no persistence, no framework types.
 *
 * NOTE: §2 shows calculate(taxableBase, rules) but §4 requires currency to build Money
 * values. Signature extended to calculate(taxableBase, currency, rules) per §4.
 */
public final class TaxCalculationService {

    public CalculationComponents calculate(BigDecimal taxableBase,
                                            Currency currency,
                                            List<TaxRuleSnapshot> rules) {
        validatePreconditions(taxableBase, currency, rules);

        var components = rules.stream()
                .sorted(Comparator.comparingInt(s -> s.taxType().ordinal()))
                .map(snapshot -> computeComponent(taxableBase, currency, snapshot))
                .collect(Collectors.toList());

        var zero = Money.builder()
                .amount(BigDecimal.ZERO.setScale(2))
                .currency(currency)
                .build();
        var totalTax = components.stream()
                .map(TaxComponent::amount)
                .reduce(zero, Money::add);

        return CalculationComponents.builder()
                .components(components)
                .totalTax(totalTax)
                .build();
    }

    private TaxComponent computeComponent(BigDecimal taxableBase,
                                           Currency currency,
                                           TaxRuleSnapshot snapshot) {
        var rate = snapshot.rateDecimal().value();
        BigDecimal intermediate;
        try {
            intermediate = taxableBase.multiply(rate, MathContext.DECIMAL128);
        } catch (ArithmeticException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "taxableBase magnitude causes arithmetic overflow");
        }
        var amount = intermediate.setScale(2, RoundingMode.HALF_EVEN);

        return TaxComponent.builder()
                .taxType(snapshot.taxType())
                .taxableBase(taxableBase.setScale(4, RoundingMode.HALF_EVEN))
                .rate(rate)
                .amount(Money.builder().amount(amount).currency(currency).build())
                .build();
    }

    private void validatePreconditions(BigDecimal taxableBase,
                                        Currency currency,
                                        List<TaxRuleSnapshot> rules) {
        if (taxableBase == null)
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "taxableBase must not be null");
        if (taxableBase.compareTo(BigDecimal.ZERO) <= 0)
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "taxableBase must be positive");
        if (taxableBase.scale() > 4)
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "taxableBase scale " + taxableBase.scale() + " exceeds maximum 4");
        if (currency == null)
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "currency must not be null");
        if (rules == null || rules.isEmpty())
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "rules must not be empty");

        rules.stream()
                .filter(s -> s.status() != RuleStatus.PUBLISHED)
                .findFirst()
                .ifPresent(s -> { throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "snapshot " + s.id() + " is not PUBLISHED"); });

        rules.stream()
                .collect(Collectors.groupingBy(TaxRuleSnapshot::taxType, Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .findFirst()
                .ifPresent(e -> { throw new DomainException(ErrorCode.VALIDATION_ERROR,
                        "duplicate TaxType " + e.getKey().name() + " in rules"); });
    }
}
