## Why

The next incomplete TODO item is the main tech stack upgrade, and the desired end state is now explicit rather than approximate. The repository is still pinned to a tightly coupled legacy baseline of Java 8, Gradle 4.2.1, Groovy 2.4.x, and Spock 1.3, which increases maintenance risk, limits dependency upgrades, and leaves the build on deprecated Gradle DSL patterns.

This should be addressed now because the repo already has automated tests as a regression net, and the updated research shows that the intended final target is real and supportable upstream: Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4 with the Groovy 5 variant. The preferred execution path is a direct one-shot landing on that stack, while explicitly allowing a temporary intermediate bridge only if the direct attempt is blocked by Groovy-major or legacy-library incompatibilities.

## What Changes

- Upgrade the repository from the current Java 8 / Gradle 4.2.1 / Groovy 2.4-era build baseline to the final target baseline of Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4-groovy-5.0.
- Migrate `build.gradle` off deprecated Gradle configurations and legacy application-plugin syntax so the project builds on Gradle 9.6.1.
- Migrate Groovy dependency coordinates from `org.codehaus.groovy` to `org.apache.groovy` and align project/test dependencies with Groovy 5.
- Upgrade the test stack from Spock 1.3 to Spock 2.4-groovy-5.0 and keep `./gradlew test` as the primary verification path.
- Validate the oldest legacy dependencies early, especially `http-builder-ng-apache` and WireMock, and replace or upgrade only those that block the direct final-target upgrade.
- Replace `http-builder-ng-apache` with a small in-repo wrapper around the JDK `java.net.http.HttpClient`, keeping the wrapper intentionally easy to understand, well-documented, and aligned with the project's current lightweight YNAB API needs.
- Keep WireMock-based integration tests as the HTTP integration-test strategy after the client migration.
- If the direct jump is blocked, use the smallest temporary bridge necessary—most likely a Groovy 4 / Spock 2.4-groovy-4.0 stabilization step—without changing the ultimate target.
- Update repository docs and OpenSpec context that currently hard-code Java 8 / Gradle 4 assumptions.

## Capabilities

### New Capabilities
- `modernized-build-toolchain`: Define the supported Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 baseline plus the required build and verification behavior for this CLI project.

### Modified Capabilities
- `automated-test-coverage`: Update the test-tooling compatibility requirement so the automated suite remains runnable and authoritative on the upgraded toolchain rather than only on the legacy Java 8 / Groovy 2.4 / Gradle 4 baseline.

## Impact

- Affected code: `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew*`, a new in-repo JDK `HttpClient` wrapper for YNAB API access, and any source or test files that require small compatibility changes for Groovy 5 / Spock 2.4 behavior.
- Affected dependencies/tooling: Java baseline, Gradle wrapper, Groovy runtime, Spock, and any incompatible legacy libraries discovered during early validation.
- Runtime/environment: `YNAB_ACCESS_TOKEN` remains required; `JAVA_HOME` and documented run/test commands will change to the new supported JDK baseline.
- Documentation/config: `README.md`, `AGENTS.md`, `openspec/config.yaml`, and the exploration/design/tasks under `openspec/changes/upgrade-main-tech-stack-phase-1/`.
- Verification: change completion will require real build/test validation on the upgraded stack, with an explicit first attempt at the final target and a documented bridge plan only if blockers force it.
