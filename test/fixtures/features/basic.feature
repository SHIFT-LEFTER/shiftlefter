Feature: Basic test feature

  A simple feature for integration testing.

  Scenario: Basic passing scenario
    Given I have 5 items in my cart
    When I add 3 more items
    Then I should have 8 items total
