## 1. Lock the final target baseline

- [x] 1.1 Record the explicit final target versions: Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4-groovy-5.0.
- [x] 1.2 Update the proposal/design/docs references so the change targets that final one-shot baseline explicitly rather than an open-ended modernization goal.

## 2. Upgrade the wrapper and build definition for Gradle 9

- [x] 2.1 Upgrade the Gradle wrapper and related wrapper files to Gradle 9.6.1.
- [x] 2.2 Update `build.gradle` from legacy Gradle 4-era dependency configurations and application-plugin syntax to the supported syntax required by Gradle 9.
- [x] 2.3 Migrate Groovy dependencies from `org.codehaus.groovy` to `org.apache.groovy` and set the selected Groovy 5.0.6 / Spock 2.4-groovy-5.0 versions.

## 3. Validate direct one-shot viability on the final target

- [x] 3.1 Attempt a direct build/test run on Java 25 + Gradle 9.6.1 + Groovy 5.0.6 + Spock 2.4-groovy-5.0 and capture every blocking error precisely.
- [x] 3.2 Validate `http-builder-ng-apache` on the selected final stack as an early runtime viability check and decide whether it can remain in phase 1.
- [x] 3.3 Validate the existing WireMock-based tests on the selected final stack and decide whether WireMock can remain or must be upgraded/replaced.
- [x] 3.4 Make the smallest production and test code changes required for Groovy 5 / Spock 2.4 / toolchain compatibility while preserving current CLI behavior and YNAB semantics.

## 4. Apply the fallback bridge only if required

- [x] 4.1 If the direct final-target attempt fails, document the exact blocker and the smallest necessary bridge sequence before continuing.
- [ ] 4.2 Prefer a Groovy 4 / Spock 2.4-groovy-4.0 bridge step if a temporary intermediate language/test stack is needed.
- [x] 4.3 Complete the required HTTP-client migration by replacing `http-builder-ng` with a small documented wrapper over JDK `HttpClient`, while keeping WireMock integration tests.
- [x] 4.4 Add focused wrapper tests plus migrated WireMock integration coverage for the new HTTP client path.

## 5. Prove the upgraded workflow and update repo guidance

- [x] 5.1 Run `./gradlew test` on the selected final baseline and fix any remaining build or compatibility failures.
- [x] 5.2 Run additional wrapper-based verification commands such as `./gradlew tasks --all` and `./gradlew installDist`, and perform a dry-run-safe application invocation if the upgraded runtime is buildable.
- [x] 5.3 Update `README.md`, `AGENTS.md`, and `openspec/config.yaml` to reflect the new supported toolchain, setup steps, and verification commands.
- [x] 5.4 Review the implementation against `openspec/changes/upgrade-main-tech-stack-phase-1/specs/modernized-build-toolchain/spec.md` and the automated-test-coverage delta before considering the change ready for apply.
