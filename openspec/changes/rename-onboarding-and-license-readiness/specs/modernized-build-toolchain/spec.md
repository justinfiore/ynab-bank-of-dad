## MODIFIED Requirements

### Requirement: The project SHALL define and validate the Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 build baseline
The project SHALL replace the legacy Java 8 / Gradle 4.2.1 / Groovy 2.4-era baseline with a documented supported toolchain consisting of Java 25 LTS, Gradle 9.6.1, Groovy 5.0.x, and Spock 2.4 using the Groovy 5 variant, unless a documented temporary bridge step is required during implementation to reach that end state. External-facing onboarding materials for new users SHALL also document the supported Java and Gradle workflow needed to evaluate the repository safely.

#### Scenario: Final build baseline is explicit and verified
- **WHEN** a developer inspects the repository build files and setup documentation after this change
- **THEN** they SHALL find Java 25, Gradle 9.6.1, Groovy 5.0.x, and Spock 2.4-groovy-5.0 documented as the intended supported build and test baseline, with any temporary bridge step clearly marked as transitional rather than final
- **AND** the documentation SHALL record that Java 25 is installed on this Hermes host at `/usr/lib/jvm/java-25-openjdk-amd64` for local verification of the upgraded toolchain
- **AND** the README and quick-start onboarding docs SHALL show the supported wrapper-based verification/run workflow a new user should follow
