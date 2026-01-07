Feature: Macro Happy Path
  Scenario: User logs in with macro
    Given login as alice +
    Then I should see the dashboard
