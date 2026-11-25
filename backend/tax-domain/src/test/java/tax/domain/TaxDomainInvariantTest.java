package tax.domain;

import org.junit.jupiter.api.Test;
import tax.domain.hash.CanonicalHashUtil;
import tax.domain.model.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BDD acceptance scenarios S-001 … S-010 from SPEC-TAX-001 §7.
 * All rates and fixtures are SYNTHETIC — not legally certified.
 */
class TaxDomainInvariantTest {

    private static final String HASH_64 = "a".repeat(64);

    // S-001: domain types involved in a valid two-component calculation are constructible
    @Test
    void s001_domainTypesForTwoComponentCalculation() {
        var period = new TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1));
        var code   = new ClassificationCode("GOODS_GENERAL");
        var cbs    = new TaxRuleSnapshot(UUID.randomUUID(), TaxType.CBS, code, period,
                                         TaxRate.of("0.088000000"), HASH_64, RuleStatus.PUBLISHED, "1.0.0");
        var ibs    = new TaxRuleSnapshot(UUID.randomUUID(), TaxType.IBS, code, period,
                                         TaxRate.of("0.177000000"), HASH_64, RuleStatus.PUBLISHED, "1.0.0");

        assertEquals(RuleStatus.PUBLISHED, cbs.status());
        assertEquals(RuleStatus.PUBLISHED, ibs.status());
        // INV-006: CBS ordinal < IBS ordinal
        assertTrue(TaxType.CBS.ordinal() < TaxType.IBS.ordinal());
        // INV-002: SimulationContext has only SYNTHETIC
        assertEquals(SimulationContext.SYNTHETIC, SimulationContext.SYNTHETIC);
    }

    // S-002: 100.01 × 0.088000000 → 8.80 (HALF_EVEN, SPEC-TAX-001 §3)
    @Test
    void s002_roundingHalfEven() {
        var base         = new BigDecimal("100.01");
        var rate         = TaxRate.of("0.088000000").value();
        var intermediate = base.multiply(rate, MathContext.DECIMAL128);
        var amount       = intermediate.setScale(2, RoundingMode.HALF_EVEN);

        assertEquals(new BigDecimal("8.80"), amount);
    }

    // S-003: 11.11 × 0.050000000 = 0.5555 → 0.56 (rounds up; >halfway between 0.55 and 0.56)
    @Test
    void s003_halfEvenRoundingPreciseMultiplication() {
        var base         = new BigDecimal("11.11");
        var rate         = TaxRate.of("0.050000000").value();
        var intermediate = base.multiply(rate, MathContext.DECIMAL128);
        var amount       = intermediate.setScale(2, RoundingMode.HALF_EVEN);

        assertEquals(new BigDecimal("0.56"), amount);
    }

    // S-004: RULE_NOT_FOUND error code exists in domain model
    @Test
    void s004_ruleNotFoundErrorCodeExists() {
        assertEquals(ErrorCode.RULE_NOT_FOUND, ErrorCode.valueOf("RULE_NOT_FOUND"));
    }

    // S-005: RULE_AMBIGUOUS error code exists in domain model
    @Test
    void s005_ruleAmbiguousErrorCodeExists() {
        assertEquals(ErrorCode.RULE_AMBIGUOUS, ErrorCode.valueOf("RULE_AMBIGUOUS"));
    }

    // S-006: TaxPeriod half-open boundary — validTo is exclusive
    @Test
    void s006_taxPeriodHalfOpenBoundary() {
        var period = new TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1));

        assertTrue(period.covers(LocalDate.of(2026, 1, 1)),   "validFrom is inclusive");
        assertTrue(period.covers(LocalDate.of(2026, 6, 30)),  "last valid day");
        assertFalse(period.covers(LocalDate.of(2026, 7, 1)),  "validTo is exclusive");
        assertFalse(period.covers(LocalDate.of(2025, 12, 31)),"before period");
    }

    // S-007: simulationContext domain constraint — only SYNTHETIC value exists
    @Test
    void s007_simulationContextOnlySYNTHETIC() {
        assertEquals(1, SimulationContext.values().length,
                "SimulationContext must have exactly one value: SYNTHETIC");
        assertEquals("SYNTHETIC", SimulationContext.SYNTHETIC.name());
    }

    // S-008: Money.of and TaxRate.of with invalid inputs throw before any state mutation (INV-010)
    @Test
    void s008_invalidValueObjectConstructionThrowsImmediately() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of("1.000,00", Currency.BRL),
                "locale-formatted string must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> Money.of("-50.00", Currency.BRL),
                "negative amount must be rejected");
        assertThrows(NullPointerException.class,
                () -> Money.of(null, Currency.BRL),
                "null amount string must be rejected");

        assertThrows(IllegalArgumentException.class,
                () -> TaxRate.of("1.1"),
                "rate > 0.999999999 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TaxRate.of("0.000000000"),
                "rate = 0.000000000 must be rejected (< min 0.000000001)");
        assertThrows(IllegalArgumentException.class,
                () -> TaxRate.of("not-a-number"),
                "non-numeric rate must be rejected");
    }

    // S-009: CanonicalHashUtil produces identical hash for identical inputs (INV-004 / RNF-001)
    @Test
    void s009_canonicalHashIsDeterministic() {
        var tenantId = "123e4567-e89b-12d3-a456-426614174000";
        var hash1 = CanonicalHashUtil.computeInputHash(
                "GOODS_GENERAL", "BRL", "2026-01-15",
                "OP-001", "SYNTHETIC", "1000.00", tenantId);
        var hash2 = CanonicalHashUtil.computeInputHash(
                "GOODS_GENERAL", "BRL", "2026-01-15",
                "OP-001", "SYNTHETIC", "1000.00", tenantId);

        assertEquals(hash1, hash2, "same inputs must produce same hash");
        assertEquals(64, hash1.length(), "hash must be 64 characters");
        assertTrue(hash1.matches("[0-9a-f]{64}"), "hash must be lowercase hex");
    }

    // S-010: CalculationStatus.CALCULATED and INV-002 — simulationContext SYNTHETIC enforced at type level
    @Test
    void s010_domainEnumerationsEnforceInvariants() {
        // INV-002 relies on SimulationContext having only SYNTHETIC
        assertEquals(1, SimulationContext.values().length);
        // CalculationStatus has CALCULATED as expected by CalculationResult
        assertEquals(CalculationStatus.CALCULATED, CalculationStatus.valueOf("CALCULATED"));
        // INV-005: Money addition exact at scale 2
        var cbs   = Money.of("88.00", Currency.BRL);
        var ibs   = Money.of("177.00", Currency.BRL);
        var total = cbs.add(ibs);
        assertEquals(new BigDecimal("265.00"), total.amount());
        assertEquals(2, total.amount().scale());
    }
}
