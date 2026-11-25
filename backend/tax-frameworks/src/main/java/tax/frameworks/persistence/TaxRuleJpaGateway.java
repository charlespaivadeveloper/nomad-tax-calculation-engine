package tax.frameworks.persistence;

import org.springframework.dao.DataAccessException;
import tax.domain.model.*;
import tax.usecases.gateway.RuleGatewayUnavailableException;
import tax.usecases.gateway.RuleResolutionRequest;
import tax.usecases.gateway.TaxRuleGateway;

import java.util.List;

import static java.util.Objects.requireNonNull;

public final class TaxRuleJpaGateway implements TaxRuleGateway {

    private final TaxRuleJpaRepository repository;

    public TaxRuleJpaGateway(TaxRuleJpaRepository repository) {
        this.repository = requireNonNull(repository, "repository must not be null");
    }

    @Override
    public List<TaxRuleSnapshot> findPublished(RuleResolutionRequest request)
            throws RuleGatewayUnavailableException {
        requireNonNull(request, "request must not be null");
        try {
            return repository
                    .findPublished(
                            request.tenantId(),
                            request.classificationCode().value(),
                            request.operationDate(),
                            RuleStatus.PUBLISHED)
                    .stream()
                    .map(this::toSnapshot)
                    .toList();
        } catch (DataAccessException e) {
            throw new RuleGatewayUnavailableException(e);
        }
    }

    private TaxRuleSnapshot toSnapshot(TaxRuleEntity e) {
        var period = TaxPeriod.builder()
                .validFrom(e.getValidFrom())
                .validTo(e.getValidTo())
                .build();
        return TaxRuleSnapshot.builder()
                .id(e.getId())
                .taxType(e.getTaxType())
                .classificationCode(ClassificationCode.builder()
                        .value(e.getClassificationCode())
                        .build())
                .period(period)
                .rateDecimal(TaxRate.of(e.getRateDecimal().toPlainString()))
                .snapshotHash(e.getSnapshotHash())
                .status(e.getStatus())
                .engineVersion(e.getEngineVersion())
                .build();
    }
}
