package tax.frameworks.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tax.domain.model.RuleStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TaxRuleJpaRepository extends JpaRepository<TaxRuleEntity, UUID> {

    @Query("""
            SELECT r FROM TaxRuleEntity r
            WHERE r.tenantId           = :tenantId
              AND r.classificationCode = :classificationCode
              AND r.status             = :status
              AND r.validFrom         <= :operationDate
              AND (r.validTo IS NULL OR r.validTo > :operationDate)
            """)
    List<TaxRuleEntity> findPublished(
            @Param("tenantId")           UUID tenantId,
            @Param("classificationCode") String classificationCode,
            @Param("operationDate")      LocalDate operationDate,
            @Param("status")             RuleStatus status);
}
