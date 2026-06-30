## ADDED Requirements

### Requirement: The project SHALL provide external-facing onboarding under the `YNABBankOfDad` name
The repository SHALL present the project as `YNABBankOfDad` in its primary onboarding materials for external readers, including the README title and first-run guidance, while preserving the current CLI runtime contract and YNAB integration assumptions.

#### Scenario: README presents an external-facing project identity
- **WHEN** a new reader opens the repository landing documentation
- **THEN** they SHALL see the project introduced as `YNABBankOfDad`
- **AND** the README SHALL explain the tool's purpose without assuming prior family-specific context
- **AND** the README SHALL still describe the current YNAB assumptions and operational constraints that affect safe use

### Requirement: The project SHALL provide concise first-run guidance for new users
The repository SHALL include a `QUICK_START.md` document that gives a newcomer a short, safe path from clone to evaluation of the CLI.

#### Scenario: New user follows a safe dry-run path
- **WHEN** a user follows the quick-start guide
- **THEN** the guide SHALL instruct them to set the required `YNAB_ACCESS_TOKEN`
- **AND** it SHALL document the supported Java / Gradle prerequisites needed for this repo
- **AND** it SHALL direct the user to run the tool in `--dry-run` mode before any real transaction posting
- **AND** it SHALL explain what to verify about budget/account/category naming assumptions before using the tool for live runs

### Requirement: The onboarding docs SHALL describe current limitations and safety constraints honestly
The README and quick-start flow SHALL describe the project's current hard-coded assumptions, current target audience/fit, and risks around real YNAB transaction posting rather than implying a fully generic off-the-shelf budgeting tool.

#### Scenario: New reader sees current constraints before live use
- **WHEN** a user reads the onboarding docs before running the tool
- **THEN** they SHALL be informed that the current implementation depends on exact YNAB naming conventions and currently hard-coded family/account rules
- **AND** they SHALL be advised to use `--dry-run` first because non-dry-run execution can create real YNAB transactions
