Feature: Macro Empty Expansion Error
  Scenario: Using a macro with no steps
    Given do nothing +
    Then this should never run
