Feature: Scenario Outline Test

Scenario Outline: Login as <role>
  Given I have role <role>
  When I login
  Then I see dashboard for <role>

Examples:
  | role  |
  | admin |
  | user  |