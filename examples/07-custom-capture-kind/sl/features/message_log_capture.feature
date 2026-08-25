Feature: Message-log capture — an adapter-contributed capture kind
  A green scenario that leaves a step-by-step evidence trail. The
  :message-log kind is not built in: setup.clj's adapter registry
  contributes it, and the config's :every-step policy captures the
  conversation after each step. The opening empty-inbox check captures
  nothing — an empty log returns nil, and nil means no file, no ref.
  Every step after the send lands a growing text/plain dump in the
  run's results directory.

  Scenario: Alice's SMS conversation is captured after every step
    Given :user/alice should see 0 messages to '+15550002222'
    When :user/alice sends an SMS to '+15550002222' saying 'ping'
    And :user/alice receives an SMS to '+15550002222' matching /ping/
    When :user/alice sends an SMS to '+15550002222' saying 'pong'
    Then :user/alice should see 2 messages to '+15550002222'
