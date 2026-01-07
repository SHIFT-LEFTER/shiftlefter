Feature: Simple passing feature

  Scenario: Everything passes
    Given I have 5 items in my cart
    When I add 3 more items
    Then I should have 8 items total
