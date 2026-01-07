Feature: Undefined step feature

  Scenario: Has undefined step
    Given I have 5 items in my cart
    When I do something undefined
    Then I should have 5 items total
