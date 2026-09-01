## ADDED Requirements

### Requirement: Live disposable-plan QA SHALL NOT be part of the default verification workflow
The existing `build-test` workflow SHALL continue to run on push and pull_request without YNAB tokens and SHALL NOT execute `qaAutomated` or `qaManual`. Live disposable-plan QA, when present, SHALL live in a separately gated workflow or job.

#### Scenario: Default CI stays token-free
- **WHEN** a pull request or push triggers the default `ci` workflow
- **THEN** that workflow SHALL run `./gradlew testAll` and `./gradlew installDist`
- **AND** it SHALL NOT require disposable-plan token secrets
- **AND** it SHALL NOT invoke `./gradlew qaAutomated` or `./gradlew qaManual`
