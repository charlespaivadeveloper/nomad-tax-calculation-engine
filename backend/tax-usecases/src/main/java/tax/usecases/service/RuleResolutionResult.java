package tax.usecases.service;

import lombok.Builder;

import tax.domain.model.TaxRuleSnapshot;
import tax.usecases.boundary.DomainError;

import java.util.List;

/**
 * Sealed result of rule resolution (SPEC-TAX-002 §7).
 */
public sealed interface RuleResolutionResult
        permits RuleResolutionResult.Success, RuleResolutionResult.Failure {

    @Builder
    record Success(List<TaxRuleSnapshot> snapshots) implements RuleResolutionResult {
        public Success {
            snapshots = List.copyOf(snapshots);
        }
    }

    @Builder
    record Failure(DomainError error) implements RuleResolutionResult {}
}
