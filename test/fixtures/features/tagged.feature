@suite
Feature: Tagged fixture for tag-filter tests

  @fast
  Scenario: Fast scenario
    Given I have 5 items in my cart
    When I add 3 more items
    Then I should have 8 items total

  @slow
  Scenario: Slow scenario
    Given I have 1 items in my cart
    When I add 1 more items
    Then I should have 2 items total

  @fast @wip
  Scenario: Fast but WIP scenario
    Given I have 2 items in my cart
    When I add 2 more items
    Then I should have 4 items total

  Scenario: Untagged scenario
    Given I have 4 items in my cart
    When I add 4 more items
    Then I should have 8 items total

  @ruled
  Rule: A tagged rule

    Scenario: Scenario under the tagged rule
      Given I have 3 items in my cart
      When I add 3 more items
      Then I should have 6 items total
