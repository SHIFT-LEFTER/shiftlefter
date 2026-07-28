Feature: Login page

  Scenario: Page loads with expected elements
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    Then :user should see the title 'Login'
    And :user should see {:id "username"}
    And :user should see {:id "password"}
    And :user should see {:css "button[type='submit']"}

  Scenario: Successful login
    When :user opens the browser to 'http://127.0.0.1:9094/login'
    And :user fills {:id "username"} with 'alice'
    And :user fills {:id "password"} with 'secret1'
    And :user clicks {:css "button[type='submit']"}
    Then :user should see 'Welcome, alice!'
    And :user should be on '/dashboard'
