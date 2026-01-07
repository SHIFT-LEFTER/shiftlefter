@feature-tag
Feature: Outline Test Feature
  Test fixture for Scenario Outline pickle extensions (BIRDSONG §4.3)

  @outline-tag
  Scenario Outline: Login as <role>
    Given I am on the login page
    When I enter "<username>" and "<password>"
    Then I should see the <role> dashboard

    @examples-tag
    Examples: Valid users
      | role    | username      | password   |
      | admin   | admin@test    | secret123  |
      | user    | user@test     | pass456    |
      | guest   | guest@test    | guest789   |
