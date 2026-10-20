Feature: Polling oracle

  A passing oracle that polls until its predicate answers (sl-tcsq): the
  first scenario needs three polls and renders the count; the second
  answers on the first poll and renders like a step that never polled.

  Scenario: Oracle answers on the third poll
    Given a counter that answers on attempt 3
    When I wait for the counter to answer
    Then the counter answered on attempt 3

  Scenario: Oracle answers on the first poll
    Given a counter that answers on attempt 1
    When I wait for the counter to answer
    Then the counter answered on attempt 1
