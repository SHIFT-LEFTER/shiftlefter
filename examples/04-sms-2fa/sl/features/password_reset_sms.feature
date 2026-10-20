Feature: Two-factor password reset via SMS
  One scenario, two interfaces, zero glue: a real browser drives the
  reset flow; the second factor arrives by SMS. Reset.smsCode names the
  message's declared shape (sl/glossary/intents/reset.edn); the match
  binds {smsCode}, and the next step types it back — no custom step
  definitions anywhere.

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And :user/alice fills Login.email with 'alice@example.com'
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Enter Verification Code'
    When :user/alice receives an SMS to '+15550001111' matching Reset.smsCode
    And :user/alice fills Login.code with {smsCode}
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Code verified for alice'
