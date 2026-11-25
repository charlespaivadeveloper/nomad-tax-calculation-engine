package tax.usecases.gateway;

import tax.domain.model.TaxRuleSnapshot;

import java.util.List;

/**
 * Output-boundary gateway for rule resolution (SPEC-TAX-002 §6).
 * Returns only PUBLISHED snapshots matching tenant, classificationCode and date.
 * No Spring/JPA/persistence types. Concrete adapter lives in tax-frameworks.
 */
@FunctionalInterface
public interface TaxRuleGateway {

    /**
     * @return all PUBLISHED snapshots matching the request; empty list if none found
     * @throws RuleGatewayUnavailableException when the backing store is unreachable
     */
    List<TaxRuleSnapshot> findPublished(RuleResolutionRequest request)
            throws RuleGatewayUnavailableException;
}
