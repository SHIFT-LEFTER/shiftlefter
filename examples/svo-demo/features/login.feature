Feature: User Login
  As a user of the system
  I want to log in with my credentials
  So that I can access my account

  # This feature demonstrates ShiftLefter's SVO validation system.
  # Steps use Subject-Verb-Object patterns that get validated against glossaries.

  Background:
    Given the login page is loaded

  @smoke
  Scenario: Successful login with valid credentials
    # SVO-validated steps: Alice (subject) performs actions (verbs) on targets (objects)
    When Alice fills the username field with "alice@example.com"
    And Alice fills the password field with "correct-password"
    And Alice clicks the login button
    Then Alice sees the welcome message
    And Alice sees her account dashboard

  Scenario: Failed login with invalid password
    When Bob fills the username field with "bob@example.com"
    And Bob fills the password field with "wrong-password"
    And Bob clicks the login button
    Then Bob sees an error message "Invalid credentials"
    And Bob sees the login form still visible

  Scenario: Guest user attempts restricted access
    # Guest is a valid subject representing unauthenticated visitors
    When Guest navigates to the account settings page
    Then Guest sees the login prompt
    And Guest sees a message "Please log in to continue"

  @admin
  Scenario: Admin user login
    When Admin fills the username field with "admin@example.com"
    And Admin fills the password field with "admin-secret"
    And Admin clicks the login button
    Then Admin sees the admin dashboard
    And Admin sees the user management link
