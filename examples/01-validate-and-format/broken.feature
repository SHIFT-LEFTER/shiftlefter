Feature: Missing scenario
  Given a step with no scenario
  When this is not valid Gherkin
  Then the parser should reject it
