Feature: Macro Recursion Error
  Scenario: Using a macro with nested macro call
    Given recursive call +
    Then this should never run
