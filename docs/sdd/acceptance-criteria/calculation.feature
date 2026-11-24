Feature: Synthetic deterministic tax calculation
  Scenario: Calculate two synthetic tax components
    Given published synthetic rule "DEMO-2026-V1"
    And taxable base is BRL 1000.00
    And CBS rate is 0.009
    And IBS rate is 0.001
    When a calculation is requested
    Then CBS amount is BRL 9.00
    And IBS amount is BRL 1.00
    And total amount is BRL 10.00
    And the response is labeled SYNTHETIC
    And rule and engine versions are recorded

  Scenario: Missing rule
    Given no applicable published rule exists
    When a calculation is requested
    Then the request fails with RULE_NOT_FOUND
    And no tax rate is silently assumed

  Scenario: Duplicate request
    Given a calculation succeeded for an idempotency key
    When the identical request is retried with the same key
    Then the original logical result is returned
