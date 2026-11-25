package tax.domain.calculation;

import net.jqwik.api.*;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;
import tax.domain.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BDD scenarios S-DE-001..S-DE-013 (SPEC-TAX-003 §9) and
 * property tests P-001..P-006 (SPEC-TAX-003 §10).
 * All fixtures SYNTHETIC — not legally certified.
 */
class TaxCalculationServiceTest {

    private static final TaxCalculationService SVC = new TaxCalculationService();
    private static final String HASH = "a".repeat(64);
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    private static TaxRuleSnapshot snapshot(TaxType type, String rate) {
        return TaxRuleSnapshot.builder()
                .id(UUID.randomUUID())
                .taxType(type)
                .classificationCode(ClassificationCode.builder().value("GOODS_TEST").build())
                .period(TaxPeriod.builder().validFrom(FROM).build())
                .rateDecimal(TaxRate.of(rate))
                .snapshotHash(HASH)
                .status(RuleStatus.PUBLISHED)
                .engineVersion("1.0.0")
                .build();
    }

    private static TaxRuleSnapshot snapshotWithStatus(TaxType type, String rate, RuleStatus status) {
        return TaxRuleSnapshot.builder()
                .id(UUID.randomUUID())
                .taxType(type)
                .classificationCode(ClassificationCode.builder().value("GOODS_TEST").build())
                .period(TaxPeriod.builder().validFrom(FROM).build())
                .rateDecimal(TaxRate.of(rate))
                .snapshotHash(HASH)
                .status(status)
                .engineVersion("1.0.0")
                .build();
    }

    // ── BDD scenarios ──────────────────────────────────────────────────────

    @Test
    void s_de_001_standard_two_component_calculation() {
        var rules = List.of(
                snapshot(TaxType.CBS, "0.088000000"),
                snapshot(TaxType.IBS, "0.177000000"));

        var result = SVC.calculate(new BigDecimal("1000.0000"), Currency.BRL, rules);

        assertEquals(2, result.components().size());
        var cbs = result.components().get(0);
        var ibs = result.components().get(1);

        assertEquals(TaxType.CBS, cbs.taxType());
        assertEquals(new BigDecimal("1000.0000"), cbs.taxableBase());
        assertEquals(new BigDecimal("0.088000000"), cbs.rate());
        assertEquals(new BigDecimal("88.00"), cbs.amount().amount());
        assertEquals(Currency.BRL, cbs.amount().currency());

        assertEquals(TaxType.IBS, ibs.taxType());
        assertEquals(new BigDecimal("177.00"), ibs.amount().amount());

        assertEquals(new BigDecimal("265.00"), result.totalTax().amount());
        assertEquals(Currency.BRL, result.totalTax().currency());
    }

    @Test
    void s_de_002_single_cbs_only_component() {
        var result = SVC.calculate(new BigDecimal("500.0000"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.088000000")));

        assertEquals(1, result.components().size());
        assertEquals(TaxType.CBS, result.components().get(0).taxType());
        assertEquals(new BigDecimal("44.00"), result.components().get(0).amount().amount());
        assertEquals(new BigDecimal("44.00"), result.totalTax().amount());
    }

    @Test
    void s_de_003_single_ibs_only_component() {
        var result = SVC.calculate(new BigDecimal("200.0000"), Currency.BRL,
                List.of(snapshot(TaxType.IBS, "0.177000000")));

        assertEquals(1, result.components().size());
        assertEquals(TaxType.IBS, result.components().get(0).taxType());
        assertEquals(new BigDecimal("35.40"), result.components().get(0).amount().amount());
        assertEquals(new BigDecimal("35.40"), result.totalTax().amount());
    }

    @Test
    void s_de_004_half_even_round_down_dropped_less_than_half() {
        // 100.0100 × 0.088000000 = 8.800880 → setScale(2, HALF_EVEN) = 8.80
        var result = SVC.calculate(new BigDecimal("100.0100"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.088000000")));

        assertEquals(new BigDecimal("8.80"), result.components().get(0).amount().amount());
    }

    @Test
    void s_de_005_half_even_round_up_odd_last_digit() {
        // 10.0000 × 0.055500000 = 0.5550 → setScale(2, HALF_EVEN) = 0.56
        // (dropped = 0.005; last kept digit 5 is odd → round up to 6)
        // NOTE: spec example used rate=0.055000000 giving 0.5500 (exact 0.55, no half-case).
        //       Corrected to rate=0.055500000 to exercise the HALF_EVEN half-case as intended.
        var result = SVC.calculate(new BigDecimal("10.0000"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.055500000")));

        assertEquals(new BigDecimal("0.56"), result.components().get(0).amount().amount());
    }

    @Test
    void s_de_005b_half_even_round_down_even_last_digit() {
        // 3.0000 × 0.075000000 = 0.2250 → setScale(2, HALF_EVEN) = 0.22
        // (dropped = 0.005; last kept digit 2 is even → round down / stay)
        var result = SVC.calculate(new BigDecimal("3.0000"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.075000000")));

        assertEquals(new BigDecimal("0.22"), result.components().get(0).amount().amount());
    }

    @Test
    void s_de_006_zero_amount_component_included_not_suppressed() {
        // 0.0100 × 0.000100000 = 0.0000010000 → setScale(2, HALF_EVEN) = 0.00
        var result = SVC.calculate(new BigDecimal("0.0100"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.000100000")));

        assertEquals(1, result.components().size(), "zero-amount component must not be suppressed");
        assertEquals(new BigDecimal("0.00"), result.components().get(0).amount().amount());
        assertEquals(new BigDecimal("0.00"), result.totalTax().amount());
    }

    @Test
    void s_de_007_large_taxable_base_no_overflow() {
        // 999999999999999.9999 × 0.088000000 = 87999999999999.999912... → 88000000000000.00
        var result = SVC.calculate(new BigDecimal("999999999999999.9999"), Currency.BRL,
                List.of(snapshot(TaxType.CBS, "0.088000000")));

        assertEquals(new BigDecimal("88000000000000.00"),
                result.components().get(0).amount().amount(),
                "large base must stay precise with DECIMAL128");
    }

    @Test
    void s_de_008_component_ordering_ibs_first_in_input_cbs_leads_in_output() {
        // Pass IBS first, CBS second — output must be CBS, IBS
        var rules = List.of(
                snapshot(TaxType.IBS, "0.177000000"),
                snapshot(TaxType.CBS, "0.088000000"));

        var result = SVC.calculate(new BigDecimal("1000.0000"), Currency.BRL, rules);

        assertEquals(TaxType.CBS, result.components().get(0).taxType());
        assertEquals(TaxType.IBS, result.components().get(1).taxType());
    }

    @Test
    void s_de_009_determinism_thousand_calls_same_result() {
        var rules = List.of(
                snapshot(TaxType.CBS, "0.088000000"),
                snapshot(TaxType.IBS, "0.177000000"));
        var base = new BigDecimal("1234.5678");

        var first = SVC.calculate(base, Currency.BRL, rules);

        for (int i = 0; i < 999; i++) {
            var r = SVC.calculate(base, Currency.BRL, rules);
            assertEquals(first.totalTax().amount(), r.totalTax().amount(),
                    "result must be identical on call " + (i + 2));
            assertEquals(first.components().get(0).amount().amount(),
                    r.components().get(0).amount().amount());
        }
    }

    @Test
    void s_de_010_empty_rules_throws_domain_exception() {
        var ex = assertThrows(DomainException.class,
                () -> SVC.calculate(new BigDecimal("1000.0000"), Currency.BRL, List.of()));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
        assertTrue(ex.detail().contains("rules must not be empty"), ex.detail());
    }

    @Test
    void s_de_011_duplicate_tax_type_throws_domain_exception() {
        var rules = List.of(
                snapshot(TaxType.CBS, "0.088000000"),
                snapshot(TaxType.CBS, "0.095000000"));

        var ex = assertThrows(DomainException.class,
                () -> SVC.calculate(new BigDecimal("1000.0000"), Currency.BRL, rules));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
        assertTrue(ex.detail().contains("duplicate TaxType CBS"), ex.detail());
    }

    @Test
    void s_de_012_non_published_snapshot_throws_domain_exception() {
        var draft = snapshotWithStatus(TaxType.CBS, "0.088000000", RuleStatus.DRAFT);

        var ex = assertThrows(DomainException.class,
                () -> SVC.calculate(new BigDecimal("1000.0000"), Currency.BRL, List.of(draft)));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
        assertTrue(ex.detail().contains("not PUBLISHED"), ex.detail());
        assertTrue(ex.detail().contains(draft.id().toString()), ex.detail());
    }

    @Test
    void s_de_013_total_tax_equals_exact_sum_of_components() {
        var rules = List.of(
                snapshot(TaxType.CBS, "0.088000000"),
                snapshot(TaxType.IBS, "0.177000000"));

        var result = SVC.calculate(new BigDecimal("1234.5678"), Currency.BRL, rules);

        var sum = result.components().stream()
                .map(c -> c.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(result.totalTax().amount(), sum,
                "totalTax must equal exact arithmetic sum of component amounts");
    }

    // ── Precondition tests ─────────────────────────────────────────────────

    @Test
    void precondition_null_taxable_base_throws() {
        assertThrows(DomainException.class,
                () -> SVC.calculate(null, Currency.BRL,
                        List.of(snapshot(TaxType.CBS, "0.088000000"))));
    }

    @Test
    void precondition_zero_taxable_base_throws() {
        var ex = assertThrows(DomainException.class,
                () -> SVC.calculate(BigDecimal.ZERO, Currency.BRL,
                        List.of(snapshot(TaxType.CBS, "0.088000000"))));
        assertTrue(ex.detail().contains("taxableBase must be positive"), ex.detail());
    }

    @Test
    void precondition_scale_exceeds_four_throws() {
        var ex = assertThrows(DomainException.class,
                () -> SVC.calculate(new BigDecimal("1.00001"), Currency.BRL,
                        List.of(snapshot(TaxType.CBS, "0.088000000"))));
        assertTrue(ex.detail().contains("scale"), ex.detail());
    }

    // ── Property tests P-001..P-006 (jqwik) ───────────────────────────────

    @Property(tries = 200)
    void p001_determinism(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal rate) {
        var rules = List.of(snapshot(TaxType.CBS, rate.toPlainString()));
        var a = SVC.calculate(taxableBase, Currency.BRL, rules);
        var b = SVC.calculate(taxableBase, Currency.BRL, rules);
        assertEquals(a.totalTax().amount(), b.totalTax().amount());
        assertEquals(a.components().get(0).amount().amount(),
                b.components().get(0).amount().amount());
    }

    @Property(tries = 200)
    void p002_all_component_amounts_non_negative(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal rate) {
        var rules = List.of(snapshot(TaxType.CBS, rate.toPlainString()));
        var result = SVC.calculate(taxableBase, Currency.BRL, rules);
        result.components().forEach(c ->
                assertTrue(c.amount().amount().compareTo(BigDecimal.ZERO) >= 0,
                        "amount must be >= 0"));
    }

    @Property(tries = 200)
    void p003_total_consistency(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal cbsRate,
            @ForAll("validRate") BigDecimal ibsRate) {
        var rules = List.of(
                snapshot(TaxType.CBS, cbsRate.toPlainString()),
                snapshot(TaxType.IBS, ibsRate.toPlainString()));
        var result = SVC.calculate(taxableBase, Currency.BRL, rules);

        var sum = result.components().stream()
                .map(c -> c.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(result.totalTax().amount(), sum);
    }

    @Property(tries = 200)
    void p004_component_count_equals_rules_size(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal rate) {
        var rules = List.of(snapshot(TaxType.CBS, rate.toPlainString()));
        var result = SVC.calculate(taxableBase, Currency.BRL, rules);
        assertEquals(1, result.components().size());
    }

    @Property(tries = 200)
    void p005_scale_integrity(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal cbsRate,
            @ForAll("validRate") BigDecimal ibsRate) {
        var rules = List.of(
                snapshot(TaxType.CBS, cbsRate.toPlainString()),
                snapshot(TaxType.IBS, ibsRate.toPlainString()));
        var result = SVC.calculate(taxableBase, Currency.BRL, rules);

        result.components().forEach(c ->
                assertEquals(2, c.amount().amount().scale(),
                        "component amount scale must be 2"));
        assertEquals(2, result.totalTax().amount().scale(),
                "totalTax scale must be 2");
    }

    @Property(tries = 200)
    void p006_tax_type_bijection(
            @ForAll("validTaxableBase") BigDecimal taxableBase,
            @ForAll("validRate") BigDecimal cbsRate,
            @ForAll("validRate") BigDecimal ibsRate) {
        var rules = List.of(
                snapshot(TaxType.CBS, cbsRate.toPlainString()),
                snapshot(TaxType.IBS, ibsRate.toPlainString()));
        var result = SVC.calculate(taxableBase, Currency.BRL, rules);

        assertEquals(TaxType.CBS, result.components().get(0).taxType());
        assertEquals(TaxType.IBS, result.components().get(1).taxType());
    }

    // ── Arbitrary providers ────────────────────────────────────────────────

    @Provide
    Arbitrary<BigDecimal> validTaxableBase() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0001"), new BigDecimal("9999999999.9999"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> validRate() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.000000001"), new BigDecimal("0.999999999"))
                .ofScale(9);
    }
}
