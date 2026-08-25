# Starter feature — scaffolded by `sl init`. Replace with your app's real
# flows (and point the URL at your app). The steps below bind against the
# built-in web verb vocabulary; prove the whole project without a browser:
#   sl run --dry-run
Feature: Login

  Scenario: Alice reaches the login page
    When :user/alice opens the browser to 'https://example.invalid/login'
    Then :user/alice should see the title 'Login'
