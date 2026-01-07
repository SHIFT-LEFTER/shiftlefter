ShiftLefter Gherkin Parser
==========================

100% Cucumber-compatible Gherkin parser and formatter.

Requirements
------------
Java 11 or later

Quick Start
-----------
1. Extract this archive
2. Run commands using ./sl

Examples:
  ./sl fmt --check features/           # Validate .feature files
  ./sl fmt --write features/           # Format files in place
  ./sl fmt --canonical file.feature    # Print canonical format to stdout
  ./sl --help                          # Show all options

Exit Codes
----------
fmt --check:
  0 = All files valid
  1 = One or more files invalid
  2 = Path error or no files found

fmt --write:
  0 = All files processed
  1 = One or more files had errors
  2 = Path error or no files found

Documentation
-------------
Full documentation: https://github.com/shiftlefter/shiftlefter-gherkin

License
-------
MIT License
