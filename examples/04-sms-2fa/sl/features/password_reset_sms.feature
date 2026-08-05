Feature: Two-factor password reset via SMS
  One scenario, two interfaces, zero glue: a real browser drives the
  reset flow; the second factor arrives by SMS. The named group
  (?<code>...) captures the code into the scenario data plane, and
  {code} types it back — no custom step definitions anywhere.

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And :user/alice fills Login.email with 'alice@example.com'
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Enter Verification Code'
    When [:sms] :user/alice receives an SMS to '+15550001111' matching /verification code is: (?<code>\d{6})/
    And :user/alice fills Login.code with {code}
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Code verified for alice'
