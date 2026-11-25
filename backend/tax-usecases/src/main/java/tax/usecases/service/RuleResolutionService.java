package tax.usecases.service;

import tax.domain.model.ErrorCode;
import tax.domain.model.TaxRuleSnapshot;
import tax.domain.model.TaxType;
import tax.usecases.boundary.DomainError;
import tax.usecases.boundary.TaxOperationInput;
import tax.usecases.gateway.RuleGatewayUnavailableException;
import tax.usecases.gateway.RuleResolutionRequest;
import tax.usecases.gateway.TaxRuleGateway;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Use-case interactor: resolve one PUBLISHED TaxRuleSnapshot per required TaxType
 * for a given TaxOperationInput (SPEC-TAX-002 §7).
 *
 * Returns the first error encountered when iterating required types in ordinal order
 * (CBS before IBS).
 */
public final class RuleResolutionService {

    private final TaxRuleGateway gateway;
    private final List<TaxType> sortedRequiredTypes;

    public RuleResolutionService(TaxRuleGateway gateway, Set<? extends TaxType> requiredTaxTypes) {
        requireNonNull(gateway,          "gateway must not be null");
        requireNonNull(requiredTaxTypes, "requiredTaxTypes must not be null");
        if (requiredTaxTypes.isEmpty())
            throw new IllegalStateException("requiredTaxTypes must not be empty");

        this.gateway = gateway;
        this.sortedRequiredTypes = requiredTaxTypes.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .collect(Collectors.toUnmodifiableList());
    }

    public RuleResolutionResult resolve(TaxOperationInput input, UUID correlationId) {
        requireNonNull(input,         "input must not be null");
        requireNonNull(correlationId, "correlationId must not be null");

        var request = RuleResolutionRequest.builder()
                .tenantId(input.tenantId())
                .classificationCode(input.classificationCode())
                .operationDate(input.operationDate())
                .build();

        Map<TaxType, List<TaxRuleSnapshot>> byType;
        try {
            var snapshots = gateway.findPublished(request);
            byType = snapshots.stream()
                    .collect(Collectors.groupingBy(TaxRuleSnapshot::taxType));
        } catch (RuleGatewayUnavailableException e) {
            return RuleResolutionResult.Failure.builder()
                    .error(DomainError.builder()
                            .code(ErrorCode.DEPENDENCY_UNAVAILABLE)
                            .detail(e.getMessage())
                            .correlationId(correlationId)
                            .build())
                    .build();
        }

        var firstError = sortedRequiredTypes.stream()
                .flatMap(type -> {
                    var matches = byType.getOrDefault(type, List.of());
                    if (matches.isEmpty())
                        return Stream.of(DomainError.builder()
                                .code(ErrorCode.RULE_NOT_FOUND)
                                .detail("No PUBLISHED rule found for TaxType " + type.name()
                                        + ", classificationCode " + input.classificationCode().value()
                                        + ", date " + input.operationDate())
                                .correlationId(correlationId)
                                .build());
                    if (matches.size() > 1)
                        return Stream.of(DomainError.builder()
                                .code(ErrorCode.RULE_AMBIGUOUS)
                                .detail(matches.size() + " PUBLISHED rules found for TaxType " + type.name()
                                        + ", classificationCode " + input.classificationCode().value()
                                        + ", date " + input.operationDate())
                                .correlationId(correlationId)
                                .build());
                    return Stream.empty();
                })
                .findFirst();

        if (firstError.isPresent())
            return RuleResolutionResult.Failure.builder().error(firstError.get()).build();

        var resolved = sortedRequiredTypes.stream()
                .map(type -> byType.get(type).get(0))
                .collect(Collectors.toUnmodifiableList());

        return RuleResolutionResult.Success.builder().snapshots(resolved).build();
    }
}
