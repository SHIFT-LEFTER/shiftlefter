Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :user/alice opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/alice fills {:id "username"} with 'tomsmith'
    And :user/alice fills {:id "password"} with 'SuperSecretPassword!'
    And :user/alice clicks {:css "button[type='submit']"}
    Then :user/alice should see 'You logged into a secure area!'
    And :user/bob opens the browser to 'https://the-internet.herokuapp.com/login'
    And :user/bob fills {:id "username"} with 'tomsmith'
    And :user/bob fills {:id "password"} with 'SuperSecretPassword!'
    And :user/bob clicks {:css "button[type='submit']"}
    And :user/bob should see 'You logged into a secure area!'
    And :user/alice should be on '/secure'
    And :user/bob should be on '/secure'
    And pause for 3 seconds
