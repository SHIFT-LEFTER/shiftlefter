Feature: Multi-actor browser test

  Scenario: Two users login simultaneously
    When :user/alice opens the browser to 'http://127.0.0.1:9095/login'
    And :user/alice fills Login.username with 'alice'
    And :user/alice fills Login.password with 'secret1'
    And :user/alice clicks Login.submit
    Then :user/alice should see 'Welcome, alice!'
    And :user/bob opens the browser to 'http://127.0.0.1:9095/login'
    And :user/bob fills Login.username with 'bob'
    And :user/bob fills Login.password with 'secret2'
    And :user/bob clicks Login.submit
    And :user/bob should see 'Welcome, bob!'
    And :user/alice should see 'Welcome, alice!'
    And :user/alice should be on '/dashboard'
    And :user/bob should be on '/dashboard'
    And :user/alice waits 3 seconds
