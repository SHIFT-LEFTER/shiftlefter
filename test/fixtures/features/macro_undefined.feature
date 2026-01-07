Feature: Macro Undefined Error
  Scenario: Using an undefined macro
    Given nonexistent macro +
    Then this should never run
