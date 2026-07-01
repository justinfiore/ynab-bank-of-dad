## MODIFIED Requirements

### Requirement: Automated tests SHALL remain compatible with the supported repo toolchain
The automated test implementation SHALL remain compatible with the repository's supported Java, Groovy, Spock, and Gradle wrapper baseline rather than being tied only to the legacy Java 8, Groovy 2.4.x, and Gradle 4.2.1 stack, and the repository SHALL provide a documented GitHub Actions workflow that exercises `./gradlew testAll` remotely on pushes and pull requests while packaging verification is also exercised through `./gradlew installDist`. Parent/child budget syncing behavior SHALL be covered by automated tests for config validation, approved-transaction mirroring, money-movement mirroring, multi-child fan-out, dry-run suppression of side effects, and replay-protection/idempotency behavior.

#### Scenario: Test suite runs on the supported Gradle workflow
- **WHEN** a developer runs `./gradlew testAll` using the repository's documented supported Java/toolchain baseline
- **THEN** the automated tests SHALL execute successfully without requiring the legacy Java 8 / Groovy 2.4 / Gradle 4 environment described before this modernization change

#### Scenario: Remote CI exercises the same supported test path
- **WHEN** a contributor pushes a branch or opens a pull request in the GitHub-hosted repository
- **THEN** the repository's GitHub Actions CI workflow SHALL execute `./gradlew testAll` on the documented supported Java baseline
- **AND** the workflow SHALL preserve the generated test results for remote inspection

#### Scenario: Parent-child sync behavior is regression-tested locally
- **WHEN** a developer runs the repository's automated tests after implementing parent/child budget syncing
- **THEN** the suite SHALL include automated coverage for qualifying transaction sync, money-movement sync, duplicate prevention, and dry-run behavior without requiring live YNAB credentials
