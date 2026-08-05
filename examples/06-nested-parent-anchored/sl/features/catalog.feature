Feature: Parent-anchored nested addressing
  One reused ProductCard component appears under three different section
  wrappers (.featured / .sidebar / .results). Each parent collection supplies
  its own :selector; the card's inner selectors are identical everywhere. The
  feature text never mentions a selector — only semantic addresses.

  Scenario: The same card resolves correctly under each wrapper
    When :user/shopper opens the browser to 'http://localhost:9091/catalog'
    Then :user/shopper should see Dashboard.featured[1].title with text 'Widget A'
    And :user/shopper should see Dashboard.featured[2].price with text '$20'
    And :user/shopper should see Dashboard.sidebar[1].title with text 'Gadget X'
    And :user/shopper should see Dashboard.results[-1].title with text 'Thing 3'

  Scenario: Nested descent into a card's rating region
    When :user/shopper opens the browser to 'http://localhost:9091/catalog'
    Then :user/shopper should see Dashboard.featured[1].rating.stars with text '4.5'
