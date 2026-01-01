# top comment: should be preserved exactly
@smoke   @wip	@owner:qa  # inline comment after tags
Feature: Tokenizer Torture    
  This description line has trailing spaces.    
  And a tab	before this word.

  # comment between description and rule

  Rule: Checkout flows  
    Background: Setup    
      Given base user exists    
      And I am logged in

    # comment between background and scenario

    @ui @table
    Scenario: Table + DocString + blank lines      
      Given step with table
        |a|  b | c\|d |    
        | 1 |2|  3   |		
      
      And step with docstring
        """text/plain
        first line
          indented line
        trailing spaces here.    
        """

      * star keyword step
      And a step with an inline comment  # keep this comment too

  # comment between rule and outline

  @outline
  Scenario Outline: Outline with examples and tags
    Given I have <count> items
    When I remove <remove> items
    Then I should have <left> items

    Examples: Basic
      | count | remove | left |
      |  3    |  1     |  2   |

    @exampletag
    Examples: Second set
      | count | remove | left |
      | 10    |  4     |  6   |
