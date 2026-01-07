Feature: Literal plus suffix

  Verifies that " +" at end of step text is preserved literally
  and not interpreted as a macro marker.

  Scenario: Step with plus suffix
    Given login as alice +
    When I check the username
    Then the username should be "alice +"
