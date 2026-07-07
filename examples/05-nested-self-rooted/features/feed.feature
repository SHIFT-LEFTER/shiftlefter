Feature: Self-rooted nested addressing

  A consistent-markup Post component (article[data-testid='post']) self-locates
  via its :root and is mounted in two regions with no per-place wiring. The
  feature text never mentions a selector — only semantic addresses.

  Scenario: A flat timeline indexes posts and skips interleaved ads
    When :user/reader opens the browser to 'http://localhost:9092/feed'
    Then :user/reader should see Feed.post[1].author with text 'Alice'
    And  :user/reader should see Feed.post[2].author with text 'Bob'
    # The 3rd POST is Carol even though an ad cell sits between Bob and Carol —
    # the precise article[data-testid='post'] selector excludes the ad (§7.7).
    And  :user/reader should see Feed.post[3].author with text 'Carol'
    And  :user/reader should see Feed.post[-1].author with text 'Dave'

  # ---------------------------------------------------------------------------
  # Recursion + cross-type. These resolve correctly ONLY with the §8.1
  # nearest-enclosing-instance rule (bead sl-h7h). Authored to the correct
  # semantics; the post-author assertion below is RED until that resolver fix
  # lands (today it matches the quote's and comments' authors too → ambiguous).
  # ---------------------------------------------------------------------------
  Scenario: An expanded thread reads the post, its quote, and its comments
    When :user/reader opens the browser to 'http://localhost:9092/feed'
    # Descent into the quoted post and the comments works today:
    And  :user/reader should see Thread.post[1].quoted.author with text 'Zoe'
    And  :user/reader should see Thread.post[1].comment[1].author with text 'Bob'
    And  :user/reader should see Thread.post[1].comment[2].author with text 'Carol'
    # The post's OWN author — unique only under §8.1 (RED until sl-h7h):
    Then :user/reader should see Thread.post[1].author with text 'Alice'
