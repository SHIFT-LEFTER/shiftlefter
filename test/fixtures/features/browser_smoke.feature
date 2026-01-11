Feature: Browser Smoke Test
  Basic browser operations to verify WebDriver integration.

  Background:
    Given I open the browser to 'https://example.com'

  Scenario: Navigate and verify page loaded
    Then I count {:css "h1"} elements

  Scenario: Click a link
    When I click {:css "a"}
    Then I count {:css "h1"} elements

  Scenario: Fill a form field
    # Note: example.com doesn't have forms, so this would fail there
    # Use a different URL for real form testing
    When I fill {:css "input[name='email']"} with 'test@example.com'
    And I click {:css "button[type='submit']"}
