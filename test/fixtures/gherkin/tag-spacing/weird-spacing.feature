@smoke   @slow		@ui
Feature: Weird Tag Spacing

  @tag1  @tag2   @tag3
  Scenario: Multiple spaces between tags
    Given a step

  @joined@tags@here
  Scenario: Joined tags without spaces
    Given another step

  	@indented	@with-tabs
  Scenario: Tabs in tag lines
    Given yet another step
