Feature: Plan fixture

  Background:
    Given the login page is loaded

  Scenario: Login through the macro
    When Alice logs in +
    Then Alice sees the dashboard
