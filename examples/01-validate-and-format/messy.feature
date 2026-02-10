Feature:    User registration
  Background:
       Given the system has no users

    Scenario:   Register with valid email
  Given the user is on the registration page
      When the user enters "bob@example.com"
        And the user clicks register
  Then the user should receive a confirmation email
      And   the user should see "Check your inbox"

  Scenario: Duplicate email rejected
    Given the system already has "bob@example.com"
      When the user enters "bob@example.com"
    And the user clicks register
        Then the user should see "Email already taken"
