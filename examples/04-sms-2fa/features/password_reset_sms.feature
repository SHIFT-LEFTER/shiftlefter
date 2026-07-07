Feature: Two-factor password reset via SMS
  One scenario, two interfaces, no glue: a real browser drives the
  reset flow; the second factor arrives by SMS and ShiftLefter reads
  it back. The fixture server and the SMS mock share one in-memory
  log so the code the server sends is the code the test reads.

  Scenario: Alice resets her password using a code sent to her phone
    When :user/alice opens the browser to 'http://localhost:9090/reset-password'
    And :user/alice fills {:id "email"} with 'alice@example.com'
    And :user/alice clicks {:css "button[type=\"submit\"]"}
    Then :user/alice should see 'Enter Verification Code'
    When [:sms] :user/alice receives an SMS to '+15550001111' matching /verification code is: (\d{6})/
    And :user/alice fills {:id "code"} with the SMS code
    And :user/alice clicks {:css "button[type=\"submit\"]"}
    Then :user/alice should see 'Code verified for alice'
