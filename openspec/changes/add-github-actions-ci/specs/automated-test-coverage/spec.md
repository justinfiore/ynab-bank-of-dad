## MODIFIED Requirements

### Requirement: Automated tests SHALL remain compatible with the supported repo toolchain
The automated test implementation SHALL remain compatible with the repository's supported Java, Groovy, Spock, and Gradle wrapper baseline rather than being tied only to the legacy Java 8, Groovy 2.4.x, and Gradle 4.2.1 stack, and the repository SHALL provide a documented GitHub Actions workflow that exercises `./gradlew test` remotely on pushes and pull requests while packaging verification is also exercised through `./gradlew installDist`.

#### Scenario: Test suite runs on the supported Gradle workflow
- **WHEN** a developer runs `./gradlew test` using the repository's documented supported Java/toolchain baseline
- **THEN** the automated tests SHALL execute successfully without requiring the legacy Java 8 / Groovy 2.4 / Gradle 4 environment described before this modernization change

#### Scenario: Remote CI exercises the same supported test path
- **WHEN** a contributor pushes a branch or opens a pull request in the GitHub-hosted repository
- **THEN** the repository's GitHub Actions CI workflow SHALL execute `./gradlew test` on the documented supported Java baseline
- **AND** the workflow SHALL preserve the generated test results for remote inspection
