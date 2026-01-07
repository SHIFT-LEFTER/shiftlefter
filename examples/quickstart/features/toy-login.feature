Feature: Toy Login
  Scenario: Successful login
    Given I am on the login page
    When I type "user" into "username"
    And I type "pass" into "password"
    When I click "login button"
    Then I see "Welcome"