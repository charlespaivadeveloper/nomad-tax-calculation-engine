package tax.usecases.service;

import org.junit.jupiter.api.Test;
import tax.domain.model.*;
import tax.domain.model.Currency;
import tax.usecases.boundary.DomainError;
import tax.usecases.boundary.TaxOperationInput;
import tax.usecases.gateway.RuleGatewayUnavailableException;
import tax.usecases.gateway.TaxRuleGateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BDD scenarios S-RR-001..S-RR-014 from SPEC-TAX-002 §8.
 * Uses hand-rolled gateway stubs — no framework wiring. All fixtures SYNTHETIC.
 */
class RuleResolutionServiceTest {

    private static final UUID TENANT_T1  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_T2  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CORR_ID    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String HASH_64  = "a".repeat(64);
    private static final LocalDate JAN15 = LocalDate.of(2026, 1, 15);

    // --- gateway stubs ---

    private static TaxRuleGateway returning(List<TaxRuleSnapshot> snapshots) {
        return req -> snapshots;
    }

    private static TaxRuleGateway unavailable() {
        return req -> { throw new RuleGatewayUnavailableException(); };
    }

    // --- helpers ---

    private static TaxRuleSnapshot snapshot(TaxType type, String classCode,
                                             LocalDate from, LocalDate to, String rate) {
        return new TaxRuleSnapshot(
                UUID.randomUUID(), type, new ClassificationCode(classCode),
                new TaxPeriod(from, to), TaxRate.of(rate), HASH_64, RuleStatus.PUBLISHED, "1.0.0");
    }

    private static TaxOperationInput input(UUID tenantId, String classCode, LocalDate date) {
        return new TaxOperationInput(
                tenantId, new OperationId("OP-TEST"), "idem-test",
                date, Currency.BRL, new ClassificationCode(classCode),
                new BigDecimal("1000.0000"), SimulationContext.SYNTHETIC);
    }

    private static RuleResolutionService service(TaxRuleGateway gw, TaxType... types) {
        return new RuleResolutionService(gw, EnumSet.copyOf(Arrays.asList(types)));
    }

    // --- S-RR-001: valid two-TaxType resolution ---

    @Test
    void s_rr_001_valid_two_tax_type_resolution_returns_cbs_then_ibs() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000");
        var ibs = snapshot(TaxType.IBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.177000000");

        var result = service(returning(List.of(cbs, ibs)), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertInstanceOf(RuleResolutionResult.Success.class, result);
        var snapshots = ((RuleResolutionResult.Success) result).snapshots();
        assertEquals(2, snapshots.size());
        assertEquals(TaxType.CBS, snapshots.get(0).taxType());
        assertEquals(TaxType.IBS, snapshots.get(1).taxType());
        assertEquals(TaxRate.of("0.088000000"), snapshots.get(0).rateDecimal());
        assertEquals(TaxRate.of("0.177000000"), snapshots.get(1).rateDecimal());
    }

    // --- S-RR-002: CBS-only required ---

    @Test
    void s_rr_002_cbs_only_required_returns_single_snapshot() {
        var cbs = snapshot(TaxType.CBS, "SERVICE_A",
                LocalDate.of(2026, 1, 1), null, "0.050000000");

        var result = service(returning(List.of(cbs)), TaxType.CBS)
                .resolve(input(TENANT_T1, "SERVICE_A", LocalDate.of(2026, 6, 1)), CORR_ID);

        assertInstanceOf(RuleResolutionResult.Success.class, result);
        var snapshots = ((RuleResolutionResult.Success) result).snapshots();
        assertEquals(1, snapshots.size());
        assertEquals(TaxType.CBS, snapshots.get(0).taxType());
    }

    // --- S-RR-003: inclusive lower bound (gateway returns snapshot for operationDate = validFrom) ---

    @Test
    void s_rr_003_gateway_returning_snapshot_for_valid_from_date_produces_success() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1), "0.088000000");

        // the gateway filters; when it returns the snapshot the interactor must accept it
        var result = service(returning(List.of(cbs)), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", LocalDate.of(2026, 4, 1)), CORR_ID);

        assertInstanceOf(RuleResolutionResult.Success.class, result,
                "snapshot returned by gateway for validFrom date must produce success");
    }

    // --- S-RR-004: exclusive upper bound (gateway returns empty for operationDate = validTo) ---

    @Test
    void s_rr_004_empty_gateway_response_produces_rule_not_found() {
        var result = service(returning(List.of()), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", LocalDate.of(2026, 7, 1)), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND, "CBS");
    }

    // --- S-RR-005: last valid day ---

    @Test
    void s_rr_005_gateway_returning_snapshot_for_last_valid_day_produces_success() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000");

        var result = service(returning(List.of(cbs)), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", LocalDate.of(2026, 6, 30)), CORR_ID);

        assertInstanceOf(RuleResolutionResult.Success.class, result,
                "snapshot returned by gateway for validTo-1 day must produce success");
    }

    // --- S-RR-006: open-ended rule (validTo = null) ---

    @Test
    void s_rr_006_gateway_returning_open_ended_snapshot_produces_success() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), null, "0.088000000");

        // open-ended: validTo=null; input uses a current date so TaxOperationInput invariant holds
        var result = service(returning(List.of(cbs)), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertInstanceOf(RuleResolutionResult.Success.class, result,
                "open-ended snapshot returned by gateway must produce success");
    }

    // --- S-RR-007: no rule found → RULE_NOT_FOUND ---

    @Test
    void s_rr_007_no_rule_for_unknown_code_returns_rule_not_found() {
        var result = service(returning(List.of()), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "UNKNOWN_CODE", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND, "UNKNOWN_CODE", "CBS", "2026-01-15");
    }

    // --- S-RR-008: DRAFT not returned by gateway → RULE_NOT_FOUND ---

    @Test
    void s_rr_008_draft_rule_filtered_by_gateway_produces_rule_not_found() {
        // gateway correctly returns empty (DRAFT excluded at persistence layer)
        var result = service(returning(List.of()), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND);
    }

    // --- S-RR-009: SUPERSEDED not returned by gateway → RULE_NOT_FOUND ---

    @Test
    void s_rr_009_superseded_rule_filtered_by_gateway_produces_rule_not_found() {
        var result = service(returning(List.of()), TaxType.CBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND);
    }

    // --- S-RR-010: overlapping PUBLISHED rules → RULE_AMBIGUOUS ---

    @Test
    void s_rr_010_two_matching_cbs_rules_produce_rule_ambiguous() {
        var cbsA = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000");
        var cbsB = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "0.095000000");

        var result = service(returning(List.of(cbsA, cbsB)), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", LocalDate.of(2026, 2, 1)), CORR_ID);

        assertFailure(result, ErrorCode.RULE_AMBIGUOUS, "2", "GOODS_GENERAL", "CBS", "2026-02-01");
    }

    // --- S-RR-011: determinism ---

    @Test
    void s_rr_011_hundred_calls_with_identical_inputs_return_identical_result() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000");
        var ibs = snapshot(TaxType.IBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.177000000");
        var svc = service(returning(List.of(cbs, ibs)), TaxType.CBS, TaxType.IBS);
        var inp = input(TENANT_T1, "GOODS_GENERAL", JAN15);

        var first = (RuleResolutionResult.Success) svc.resolve(inp, CORR_ID);
        for (int i = 1; i < 100; i++) {
            var r = (RuleResolutionResult.Success) svc.resolve(inp, CORR_ID);
            assertEquals(first.snapshots().get(0).id(), r.snapshots().get(0).id());
            assertEquals(first.snapshots().get(1).id(), r.snapshots().get(1).id());
        }
    }

    // --- S-RR-012: CBS found, IBS missing → RULE_NOT_FOUND for IBS ---

    @Test
    void s_rr_012_cbs_found_ibs_missing_returns_rule_not_found_for_ibs() {
        var cbs = snapshot(TaxType.CBS, "GOODS_GENERAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000");

        var result = service(returning(List.of(cbs)), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND, "IBS");
        // CBS must NOT appear in the error detail
        var detail = ((RuleResolutionResult.Failure) result).error().detail();
        assertFalse(detail.contains("CBS"), "Error detail must name IBS, not CBS");
    }

    // --- S-RR-013: gateway unavailable → DEPENDENCY_UNAVAILABLE ---

    @Test
    void s_rr_013_gateway_unavailable_returns_dependency_unavailable() {
        var result = service(unavailable(), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.DEPENDENCY_UNAVAILABLE, "rule store unavailable");
    }

    // --- S-RR-014: tenant isolation ---

    @Test
    void s_rr_014_gateway_returning_empty_for_tenant_produces_rule_not_found() {
        // Gateway correctly returns empty for T1 (T2's rules are never returned)
        var result = service(returning(List.of()), TaxType.CBS, TaxType.IBS)
                .resolve(input(TENANT_T1, "GOODS_GENERAL", JAN15), CORR_ID);

        assertFailure(result, ErrorCode.RULE_NOT_FOUND);
    }

    // --- invariant INV-RR-010: empty requiredTaxTypes is programming error ---

    @Test
    void inv_rr_010_empty_required_tax_types_throws_illegal_state() {
        assertThrows(IllegalStateException.class,
                () -> new RuleResolutionService(returning(List.of()), new HashSet<>()),
                "Empty requiredTaxTypes must throw IllegalStateException");
    }

    // --- assertion helpers ---

    private static void assertFailure(RuleResolutionResult result,
                                       ErrorCode expectedCode, String... detailContains) {
        assertInstanceOf(RuleResolutionResult.Failure.class, result);
        DomainError error = ((RuleResolutionResult.Failure) result).error();
        assertEquals(expectedCode, error.code());
        for (var fragment : detailContains) {
            assertTrue(error.detail().contains(fragment),
                    "Expected detail to contain '" + fragment + "' but was: " + error.detail());
        }
    }
}
