Feature: Cucumber basket

  Scenario: Eating cucumbers
    Given I have 12 cucumbers
    When I eat 5 cucumbers
    Then I should have 7 cucumbers

  Scenario: Eating too many
    Given I have 3 cucumbers
    When I eat 5 cucumbers
    Then I should have -2 cucumbers
