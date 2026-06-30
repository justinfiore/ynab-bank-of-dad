## MODIFIED Requirements

### Requirement: Automated tests SHALL remain compatible with the supported repo toolchain
The automated test implementation SHALL remain compatible with the repository's supported Java, Groovy, Spock, and Gradle wrapper baseline rather than being tied only to the legacy Java 8, Groovy 2.4.x, and Gradle 4.2.1 stack.

#### Scenario: Test suite runs on the supported Gradle workflow
- **WHEN** a developer runs `./gradlew test` using the repository's documented supported Java/toolchain baseline
- **THEN** the automated tests SHALL execute successfully without requiring the legacy Java 8 / Groovy 2.4 / Gradle 4 environment described before this modernization change
