Feature: Hooked preview fixture

  @hook=reset-db
  Scenario: Seeded scenario
    Given I have 5 items in my cart

  Scenario: Plain scenario
    Given I have 5 items in my cart
