Feature: Pending step feature

  Scenario: Step returns pending
    Given I have 5 items in my cart
    When I return pending
    Then I should have 5 items total
