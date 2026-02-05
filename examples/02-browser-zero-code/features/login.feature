Feature: Login page

  Scenario: Page loads with expected elements
    When :user opens the browser to 'https://the-internet.herokuapp.com/login'
    Then :user should see the title 'The Internet'
    And :user should see {:id "username"}
    And :user should see {:id "password"}
    And :user should see {:css "button[type='submit']"}

  Scenario: Successful login
    When :user opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user fills {:id "username"} with 'tomsmith'
    And :user fills {:id "password"} with 'SuperSecretPassword!'
    And :user clicks {:css "button[type='submit']"}
    Then :user should see 'You logged into a secure area!'
    And :user should be on '/secure'
