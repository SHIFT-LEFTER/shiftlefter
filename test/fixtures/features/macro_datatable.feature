Feature: Macro DataTable Error
  Scenario: Macro call with data table argument
    Given login as alice +
      | username | password |
      | alice    | secret   |
    Then this should never run
