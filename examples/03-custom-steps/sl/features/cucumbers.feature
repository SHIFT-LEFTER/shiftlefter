Feature: Cucumber basket

  Scenario: Eating cucumbers
    Given :user has 12 cucumbers
    When :user eats 5 cucumbers
    Then I should have 7 cucumbers

  Scenario: Eating too many
    Given :user has 3 cucumbers
    When :user eats 5 cucumbers
    Then I should have -2 cucumbers
