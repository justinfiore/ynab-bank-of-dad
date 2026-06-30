# modernized-build-toolchain Specification

## Purpose
Define the supported build, test, and packaging baseline for YNABBankOfDad after the Java 25 / Gradle 9 / Groovy 5 modernization, including the documented repo-local JDK HttpClient migration that replaced the archived legacy HTTP dependency.
## Requirements
### Requirement: The project SHALL define and validate the Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 build baseline
The project SHALL replace the legacy Java 8 / Gradle 4.2.1 / Groovy 2.4-era baseline with a documented supported toolchain consisting of Java 25 LTS, Gradle 9.6.1, Groovy 5.0.x, and Spock 2.4 using the Groovy 5 variant, unless a documented temporary bridge step is required during implementation to reach that end state. External-facing onboarding materials for new users SHALL also document the supported Java and Gradle workflow needed to evaluate the repository safely.

#### Scenario: Final build baseline is explicit and verified
- **WHEN** a developer inspects the repository build files and setup documentation after this change
- **THEN** they SHALL find Java 25, Gradle 9.6.1, Groovy 5.0.x, and Spock 2.4-groovy-5.0 documented as the intended supported build and test baseline, with any temporary bridge step clearly marked as transitional rather than final
- **AND** the documentation SHALL record that Java 25 is installed on this Hermes host at `/usr/lib/jvm/java-25-openjdk-amd64` for local verification of the upgraded toolchain
- **AND** the README and quick-start onboarding docs SHALL show the supported wrapper-based verification/run workflow a new user should follow

### Requirement: The project SHALL use Gradle-9-compatible build syntax on the upgraded wrapper
The project SHALL migrate legacy Gradle dependency configurations and application-plugin declarations to syntax that is supported by Gradle 9.6.1 so the project can build without relying on deprecated Gradle 4-era constructs.

#### Scenario: Build script no longer depends on deprecated legacy configurations
- **WHEN** the project is built with the Gradle 9.6.1 wrapper
- **THEN** the main build script SHALL use supported dependency and application-plugin configuration patterns instead of legacy `compile`, `testCompile`, and top-level `mainClassName` usage

### Requirement: Modernization SHALL preserve the current CLI runtime contract
The toolchain modernization SHALL preserve the current runtime behavior of the CLI application, including the requirement for `YNAB_ACCESS_TOKEN`, support for `--date`, `--dry-run`, `--help`, and any approved config-file flag, the dry-run-safe execution pattern, and runtime-config-driven YNAB lookup behavior rather than hard-coded personal naming assumptions.

#### Scenario: Supported toolchain changes do not redefine application behavior
- **WHEN** a developer reads the updated docs and runs the application on the supported toolchain
- **THEN** the documented runtime prerequisites and observable CLI behavior SHALL remain aligned with the pre-modernization contract except for the updated Java/toolchain prerequisites

### Requirement: The upgraded build SHALL support a documented bridge only when required to reach the final target
If a direct one-shot landing on the final Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 baseline is blocked, the modernization plan SHALL document the smallest necessary temporary bridge step and keep the final target explicit rather than silently redefining success as the intermediate stack.

#### Scenario: Temporary bridge steps remain transitional
- **WHEN** the direct final-target attempt exposes a blocker that requires an intermediate stack change
- **THEN** the change artifacts SHALL document the blocker, identify the temporary bridge step, and preserve the Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 baseline as the intended end state

### Requirement: The upgraded build SHALL continue to support normal distribution tasks
The upgraded toolchain SHALL continue to support the repository's normal build workflow, including test execution and creation of the distributable application output through the Gradle wrapper.

#### Scenario: Build workflow remains available after modernization
- **WHEN** a developer runs the repository's documented Gradle verification and packaging commands on the upgraded toolchain
- **THEN** the wrapper-based workflow SHALL continue to provide test execution and application distribution output without requiring a separate non-Gradle build path

### Requirement: The migrated HTTP layer SHALL use a documented in-repo wrapper over JDK `HttpClient`
When replacing the legacy `http-builder-ng` dependency, the project SHALL use a small in-repo wrapper around the JDK `java.net.http.HttpClient` rather than adding another third-party HTTP client dependency purely for ergonomics.

#### Scenario: HTTP client replacement is lightweight and understandable
- **WHEN** a developer inspects the migrated HTTP integration code after this change
- **THEN** they SHALL find a wrapper over JDK `HttpClient` that is documented in code and scoped to the YNAB API behaviors this project actually uses
- **AND** the repo documentation SHALL explain that this approach was chosen because it avoids another client dependency while still allowing a simple, maintainable call surface

### Requirement: HTTP migration SHALL preserve WireMock-backed integration verification
The replacement of `http-builder-ng` SHALL keep WireMock-based integration tests as part of the verification strategy for real HTTP request/response behavior.

#### Scenario: Integration tests still prove the YNAB HTTP contract after the client migration
- **WHEN** the automated test suite runs on the upgraded toolchain
- **THEN** the suite SHALL include WireMock-backed tests that verify authorization headers, request paths, JSON request bodies, and representative parsed responses for the migrated HTTP client path

