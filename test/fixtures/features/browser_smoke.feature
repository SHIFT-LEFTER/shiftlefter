Feature: Browser Smoke Test
  Basic browser operations to verify WebDriver integration.

  Background:
    Given :user opens the browser to 'https://example.com'

  Scenario: Navigate and verify page loaded
    Then :user should see {:css "h1"}

  Scenario: Click a link
    When :user clicks {:css "a"}
    Then :user should see {:css "h1"}

  Scenario: Fill a form field
    # Note: example.com doesn't have forms, so this would fail there
    # Use a different URL for real form testing
    When :user fills {:css "input[name='email']"} with 'test@example.com'
    And :user clicks {:css "button[type='submit']"}
