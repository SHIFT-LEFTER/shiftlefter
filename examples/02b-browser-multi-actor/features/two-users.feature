Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :alice opens the browser to 'https://the-internet.herokuapp.com/login'
    And :alice fills {:id "username"} with 'tomsmith'
    And :alice fills {:id "password"} with 'SuperSecretPassword!'
    And :alice clicks {:css "button[type='submit']"}
    Then :alice should see 'You logged into a secure area!'
    And :bob opens the browser to 'https://the-internet.herokuapp.com/login'
    And :bob fills {:id "username"} with 'tomsmith'
    And :bob fills {:id "password"} with 'SuperSecretPassword!'
    And :bob clicks {:css "button[type='submit']"}
    And :bob should see 'You logged into a secure area!'
    And :alice should be on '/secure'
    And :bob should be on '/secure'
    And pause for 3 seconds
