Feature: Macro Scenario Outline Error
  Scenario Outline: Macro in scenario outline
    Given login as <user> +
    Then I should see <page>

    Examples:
      | user  | page      |
      | alice | dashboard |
      | bob   | profile   |
