## ADDED Requirements

### Requirement: The repository SHALL include an explicit root-level MIT license file
The project SHALL commit a root-level `LICENSE` file containing the MIT License text so prospective users and contributors can determine the permitted reuse terms directly from the repository.

#### Scenario: Repository visitor can identify license terms from the root
- **WHEN** a user inspects the repository root or hosting UI
- **THEN** they SHALL find a `LICENSE` file committed at the top level of the repository
- **AND** that file SHALL contain the full text of the MIT License

### Requirement: Onboarding docs SHALL state the active MIT project license
The README SHALL identify MIT as the active project license and point readers to the root-level `LICENSE` file.

#### Scenario: Reader learns licensing from onboarding docs
- **WHEN** a reader reviews the README to evaluate reuse
- **THEN** they SHALL see which license governs the repository
- **AND** they SHALL be directed to the root-level `LICENSE` file for the full terms

### Requirement: Public-hosting readiness docs SHALL reflect the current hosting state accurately
Repository planning and onboarding references that discuss public-hosting readiness SHALL reflect that the repo is already hosted on GitHub and that the remaining readiness work is licensing and onboarding clarity, not migration to a new host.

#### Scenario: Backlog/docs no longer claim GitHub hosting is still pending
- **WHEN** a maintainer reviews the backlog or related readiness notes after this change
- **THEN** they SHALL not see the remaining public-hosting-readiness work described as moving the repo to GitHub
- **AND** the remaining work SHALL instead focus on licensing and external-reader readiness
