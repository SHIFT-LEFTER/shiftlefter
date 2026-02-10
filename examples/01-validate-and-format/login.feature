Feature: User login

  Scenario: Successful login with valid credentials
    Given the user is on the login page
    When the user enters "alice@example.com" and "correct-password"
    And the user clicks the login button
    Then the user should see the dashboard

  Scenario: Failed login shows error
    Given the user is on the login page
    When the user enters "alice@example.com" and "wrong-password"
    And the user clicks the login button
    Then the user should see an error message "Invalid credentials"
