Feature: Failing step feature

  Scenario: Step throws exception
    Given I have 5 items in my cart
    When I trigger an error
    Then I should have 5 items total
