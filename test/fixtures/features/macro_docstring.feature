Feature: Macro DocString Error
  Scenario: Macro call with docstring argument
    Given login as alice +
      """
      This docstring should cause an error
      """
    Then this should never run
