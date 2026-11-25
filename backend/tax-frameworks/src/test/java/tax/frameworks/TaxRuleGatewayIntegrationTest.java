package tax.frameworks;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tax.domain.hash.SnapshotHashUtil;
import tax.domain.model.*;
import tax.domain.model.Currency;
import tax.frameworks.persistence.TaxRuleJpaGateway;
import tax.frameworks.persistence.TaxRuleJpaRepository;
import tax.usecases.gateway.RuleGatewayUnavailableException;
import tax.usecases.gateway.RuleResolutionRequest;
import tax.usecases.service.RuleResolutionResult;
import tax.usecases.service.RuleResolutionService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Integration tests IT-RR-001..IT-RR-007 from SPEC-TAX-002 §9.
 * Requires Docker (Testcontainers). All fixtures SYNTHETIC.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaxRuleGatewayIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled",           () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto",   () -> "none");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
              .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
              .load()
              .migrate();
    }

    private static final UUID TENANT_T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CORR_ID   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final LocalDate JAN15 = LocalDate.of(2026, 1, 15);

    @Autowired
    private TaxRuleJpaRepository repository;

    @Autowired
    private DataSource dataSource;

    private TaxRuleJpaGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new TaxRuleJpaGateway(repository);
    }

    // --- IT-RR-001: two PUBLISHED snapshots, gateway returns both ---

    @Test
    @Order(1)
    void it_rr_001_gateway_returns_both_cbs_and_ibs_for_valid_date() throws Exception {
        insertRule(TENANT_T1, TaxType.CBS, "GOODS_IT001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000", "PUBLISHED");
        insertRule(TENANT_T1, TaxType.IBS, "GOODS_IT001",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.177000000", "PUBLISHED");

        var request = new RuleResolutionRequest(TENANT_T1,
                new ClassificationCode("GOODS_IT001"), JAN15);
        var snapshots = gateway.findPublished(request);

        assertEquals(2, snapshots.size(), "IT-RR-001: must return exactly 2 snapshots");
        assertTrue(snapshots.stream().anyMatch(s -> s.taxType() == TaxType.CBS));
        assertTrue(snapshots.stream().anyMatch(s -> s.taxType() == TaxType.IBS));
        snapshots.forEach(s -> assertEquals(RuleStatus.PUBLISHED, s.status()));
        snapshots.forEach(s -> assertTrue(s.period().covers(JAN15)));
    }

    // --- IT-RR-002: tenant isolation ---

    @Test
    @Order(2)
    void it_rr_002_tenant_isolation_t1_cannot_see_t2_rules() throws Exception {
        insertRule(TENANT_T2, TaxType.CBS, "GOODS_IT002",
                LocalDate.of(2026, 1, 1), null, "0.088000000", "PUBLISHED");

        var request = new RuleResolutionRequest(TENANT_T1,
                new ClassificationCode("GOODS_IT002"), JAN15);
        var snapshots = gateway.findPublished(request);

        assertTrue(snapshots.isEmpty(), "IT-RR-002: T1 must not see T2's rules");
    }

    // --- IT-RR-003: overlapping rules → interactor returns RULE_AMBIGUOUS ---

    @Test
    @Order(3)
    void it_rr_003_two_overlapping_cbs_rules_produce_rule_ambiguous() throws Exception {
        insertRule(TENANT_T1, TaxType.CBS, "GOODS_IT003",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000", "PUBLISHED");
        insertRule(TENANT_T1, TaxType.CBS, "GOODS_IT003",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), "0.095000000", "PUBLISHED");

        var svc = new RuleResolutionService(gateway, EnumSet.of(TaxType.CBS, TaxType.IBS));
        var input = new tax.usecases.boundary.TaxOperationInput(
                TENANT_T1, new OperationId("OP-003"), "idem-003",
                LocalDate.of(2026, 2, 1), Currency.BRL,
                new ClassificationCode("GOODS_IT003"),
                new BigDecimal("1000.0000"), SimulationContext.SYNTHETIC);

        var result = svc.resolve(input, CORR_ID);

        assertInstanceOf(RuleResolutionResult.Failure.class, result);
        assertEquals(ErrorCode.RULE_AMBIGUOUS,
                ((RuleResolutionResult.Failure) result).error().code(), "IT-RR-003");
    }

    // --- IT-RR-004: repository unavailable → RuleGatewayUnavailableException ---

    @Test
    @Order(4)
    void it_rr_004_unavailable_repository_throws_rule_gateway_unavailable() {
        var failingRepo = Mockito.mock(TaxRuleJpaRepository.class);
        Mockito.when(failingRepo.findPublished(any(), any(), any(), any()))
               .thenThrow(new DataAccessResourceFailureException("simulated unavailability"));
        var failingGateway = new TaxRuleJpaGateway(failingRepo);
        var request = new RuleResolutionRequest(TENANT_T1,
                new ClassificationCode("GOODS_IT004"), JAN15);

        assertThrows(RuleGatewayUnavailableException.class,
                () -> failingGateway.findPublished(request),
                "IT-RR-004: DataAccessException must be wrapped as RuleGatewayUnavailableException");
    }

    // --- IT-RR-005: open-ended snapshot (valid_to = NULL) ---

    @Test
    @Order(5)
    void it_rr_005_open_ended_snapshot_returned_for_far_future_date() throws Exception {
        insertRule(TENANT_T1, TaxType.CBS, "GOODS_IT005",
                LocalDate.of(2026, 1, 1), null, "0.088000000", "PUBLISHED");

        var request = new RuleResolutionRequest(TENANT_T1,
                new ClassificationCode("GOODS_IT005"), LocalDate.of(2030, 12, 31));
        var snapshots = gateway.findPublished(request);

        assertEquals(1, snapshots.size(), "IT-RR-005: open-ended snapshot must be returned for 2030");
        assertNull(snapshots.get(0).period().validTo(), "validTo must be null");
    }

    // --- IT-RR-006: snapshot hash matches stored value ---

    @Test
    @Order(6)
    void it_rr_006_returned_snapshot_hash_matches_stored_hash() throws Exception {
        var tenantId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        var from = LocalDate.of(2026, 1, 1);
        var to   = LocalDate.of(2026, 7, 1);
        var expectedHash = SnapshotHashUtil.computeSnapshotHash(
                "GOODS_IT006", "0.088000000", "PUBLISHED",
                "CBS", tenantId.toString(), from.toString(), to.toString());

        insertRuleWithHash(tenantId, TaxType.CBS, "GOODS_IT006", from, to,
                "0.088000000", "PUBLISHED", expectedHash);

        var request = new RuleResolutionRequest(tenantId,
                new ClassificationCode("GOODS_IT006"), JAN15);
        var snapshots = gateway.findPublished(request);

        assertEquals(1, snapshots.size());
        assertEquals(expectedHash, snapshots.get(0).snapshotHash(),
                "IT-RR-006: snapshotHash returned by gateway must match stored value");
        assertEquals(64, snapshots.get(0).snapshotHash().length());
        assertTrue(snapshots.get(0).snapshotHash().matches("[0-9a-f]{64}"));
    }

    // --- IT-RR-007: 50 concurrent threads → all receive identical snapshots ---

    @Test
    @Order(7)
    void it_rr_007_concurrent_reads_return_identical_snapshots() throws Exception {
        insertRule(TENANT_T1, TaxType.CBS, "GOODS_IT007",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.088000000", "PUBLISHED");
        insertRule(TENANT_T1, TaxType.IBS, "GOODS_IT007",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "0.177000000", "PUBLISHED");

        var request = new RuleResolutionRequest(TENANT_T1,
                new ClassificationCode("GOODS_IT007"), JAN15);

        var latch   = new CountDownLatch(1);
        var errors  = new AtomicInteger(0);
        var results = new CopyOnWriteArrayList<List<TaxRuleSnapshot>>();
        var executor = Executors.newFixedThreadPool(50);

        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    results.add(gateway.findPublished(request));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, errors.get(),  "IT-RR-007: no errors during concurrent reads");
        assertEquals(50, results.size(), "IT-RR-007: all threads must produce a result");
        var first = results.get(0);
        for (var r : results) {
            assertEquals(first.size(), r.size());
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.get(i).id(), r.get(i).id(),
                        "IT-RR-007: all results must reference identical snapshot IDs");
            }
        }
    }

    // --- helpers ---

    private void insertRule(UUID tenantId, TaxType type, String classCode,
                             LocalDate from, LocalDate to, String rate, String status)
            throws Exception {
        String toStr = to != null ? to.toString() : "open";
        String hash  = SnapshotHashUtil.computeSnapshotHash(
                classCode, rate, status, type.name(), tenantId.toString(),
                from.toString(), toStr);
        insertRuleWithHash(tenantId, type, classCode, from, to, rate, status, hash);
    }

    private void insertRuleWithHash(UUID tenantId, TaxType type, String classCode,
                                     LocalDate from, LocalDate to, String rate,
                                     String status, String hash) throws Exception {
        try (var c  = dataSource.getConnection();
             var ps = c.prepareStatement(
                "INSERT INTO tax_rule (id,tenant_id,tax_type,classification_code," +
                "valid_from,valid_to,rate_decimal,snapshot_hash,status,engine_version,created_at) " +
                "VALUES (gen_random_uuid(),?::uuid,?,?,?,?,?,?,?,?,now())")) {
            ps.setString(1,  tenantId.toString());
            ps.setString(2,  type.name());
            ps.setString(3,  classCode);
            ps.setDate(4,    Date.valueOf(from));
            ps.setDate(5,    to != null ? Date.valueOf(to) : null);
            ps.setBigDecimal(6, new BigDecimal(rate).setScale(9, RoundingMode.HALF_EVEN));
            ps.setString(7,  hash);
            ps.setString(8,  status);
            ps.setString(9,  "1.0.0");
            ps.executeUpdate();
        }
    }
}
