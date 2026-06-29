# Explore: Upgrade the main tech stack

## Context
TODO item 2 asks for a stack modernization pass:
- upgrade Java to the latest LTS release
- upgrade Gradle to the latest major release
- replace deprecated/incompatible libraries
- upgrade the test framework(s)
- verify Groovy compatibility across the upgraded toolchain

This repo is currently a single Groovy CLI app with tests and OpenSpec already initialized. Exploration is appropriate before creating a change because the modernization spans multiple coupled layers of the toolchain.

## Current state discovered in the repo

### Build and runtime baseline
- `build.gradle` uses legacy Gradle 4-era syntax (`apply plugin`, `compile`, `testCompile`, `mainClassName`).
- Gradle wrapper is pinned to `4.2.1` in `gradle/wrapper/gradle-wrapper.properties`.
- `openspec/config.yaml` and `AGENTS.md` both document Java 8 / Groovy 2.4.x / Gradle 4.2.1 as the current supported stack.
- `RecordAllowance.groovy` is a single Groovy script-style class acting as the application entry point.
- Automated tests now exist and pass with `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./gradlew test`.

### Dependency baseline
From `build.gradle`:
- Groovy: `org.codehaus.groovy:groovy-all:2.4.15`
- Apache Commons Lang: `3.7`
- Commons CLI: `1.4`
- SLF4J API: `1.6.1`
- Logback Classic: `1.2.3`
- HTTP client: `io.github.http-builder-ng:http-builder-ng-apache:1.0.3`
- Test framework: `org.spockframework:spock-core:1.3-groovy-2.4`
- Test helpers: `cglib-nodep:3.2.10`, `objenesis:2.6`, `wiremock:1.58`

### Constraints visible from the codebase
- Existing tests and docs assume Java 8 on this host.
- The app directly integrates with the YNAB API; behavior changes should be verified with dry-run-safe execution where possible.
- This is a small brownfield CLI, so an incremental modernization is safer than a rewrite.
- Because the app is Groovy-based, Java, Gradle, Groovy, and Spock upgrades cannot be treated independently.
- The current source only shows one likely Groovy-major migration hotspot on initial scan: the project still uses `org.codehaus.groovy` coordinates and Groovy 2-era ecosystem assumptions.

## Additional version research

### Grounded source findings
Using live upstream docs and Maven metadata:
- Gradle’s current compatibility matrix says:
  - Java 25 support for **running Gradle** starts at **Gradle 9.1.0**
  - Java 25 support for **toolchains** starts at **Gradle 9.1.0**
  - Java 26 support begins later, at **Gradle 9.4.0**
  - Gradle **9.6.1** is current and is a patch release on the Gradle 9 line
  - Gradle 9.0.0 requires **JVM 17 or higher to run the Gradle daemon**
  - Gradle 9 upgraded its embedded Groovy baseline to **Groovy 4.0.27**, and Gradle docs state that **plugins written in Groovy must use Groovy 4.x** for Gradle/plugin compatibility
- Groovy download/docs state:
  - Groovy **5.0** is the **latest stable** version
  - Groovy 5 is designed for **JDK 11+**
  - Groovy **4.0** is the previous stable line
- Groovy 4 release notes confirm:
  - Maven coordinates changed from `org.codehaus.groovy` to `org.apache.groovy`
  - Groovy 4 removed some legacy split-package compatibility shims introduced in Groovy 3
- Spock upstream metadata confirms:
  - `spock-core:2.4-groovy-5.0` exists on Maven Central
  - the Spock 2.4 Groovy-5 variant currently depends on **Groovy 5.0.3** transitively in its published POM
  - Spock 2.x still supports Java 8+ at the framework level, but its Groovy 5 variant necessarily implies JDK 11+
- Maven Central metadata confirms current target-family artifacts exist for the desired stack:
  - `org.apache.groovy:groovy-all:5.0.6`
  - `org.apache.groovy:groovy:5.0.6`
  - `org.spockframework:spock-core:2.4-groovy-5.0`
- Legacy-library findings:
  - `io.github.http-builder-ng:http-builder-ng-apache` only goes to **1.0.4** and has not been released since 2019
  - `http-builder-ng-apache:1.0.4` still pulls **Apache HttpClient 4.5.2** and old supporting pieces
  - WireMock under `com.github.tomakehurst:wiremock` effectively tops out at **2.27.2** before the line moves to `org.wiremock:wiremock:3.x`
  - WireMock 2.27.2 still pulls very old Jetty 9.2.x and SLF4J 1.7.12-era dependencies
  - WireMock 3.0.1 has the new groupId and a significantly different dependency stack (Jetty 11, Jackson 2.15.x, HttpClient5, etc.)

## Implications of the source findings

1. **Justin’s desired ultimate target is real and supportable upstream**
   - Java 25 + Gradle 9.6.1 + Groovy 5 + Spock 2.4 is not speculative; all the pieces exist and are in supported/current lines.

2. **Gradle 9.x is mandatory for a Java 25 target**
   - Since Java 25 support starts at Gradle 9.1.0, the old Gradle-8-based thinking is now obsolete for this plan.
   - Gradle 9.6.1 is comfortably above the minimum support floor.

3. **Groovy 5 is now the right ultimate language target, but it increases migration surface area**
   - Moving from Groovy 2.4 to Groovy 5 is a larger leap than Groovy 2.4 to Groovy 4.
   - The repo must absorb both the `org.codehaus.groovy` → `org.apache.groovy` coordinate change and whatever source/test compatibility issues emerge across multiple Groovy major versions.

4. **Spock 2.4 + Groovy 5 is the right ultimate test target**
   - It aligns with Justin’s desired end state and exists as a published artifact.
   - It also means the repo should stop thinking in terms of the old Spock 1.3 compiler-plugin model entirely.

5. **The biggest one-shot risks are still the ecosystem stragglers, not the headline versions**
   - `http-builder-ng-apache` is the single riskiest runtime dependency because it is old, lightly maintained, and central to the app’s YNAB integration.
   - WireMock 1.58 is also a high-risk compatibility point; if it breaks, the next decision is whether to jump to WireMock 3 directly or replace the test harness another way.
   - Gradle 9 adds another axis: buildscripts/plugins written in Groovy need Groovy 4.x compatibility with Gradle itself, while the project’s **compiled application/test sources** can still target Groovy 5 as dependencies. That means we should avoid fancy custom Groovy build logic during the migration.

## Recommended target baseline

### Ultimate target baseline
Recommend the proposal now explicitly target:
- **Java 25 LTS**
- **Gradle 9.6.1**
- **Groovy 5.0.6** as the project dependency line
- **Spock 2.4-groovy-5.0** as the test framework target

This matches Justin’s preferred end state and is backed by currently published upstream artifacts.

## One-shot vs. leap-frog analysis

### Can we likely do this in one shot?
**Maybe, but materially less certain than the earlier Java-21 / Groovy-4 plan.**

Why one-shot is still plausible:
- The codebase is small and concentrated in one application class plus a small test suite.
- The build script itself is simple and does not appear to contain custom Gradle plugin code.
- The desired endpoint versions are all real, current, and mutually plausible at the headline level.

Why one-shot is now riskier:
- The leap is larger on both the language axis (Groovy 2.4 → 5.0) and build axis (Gradle 4.2.1 → 9.6.1).
- `http-builder-ng-apache` is old enough that it may not survive the move cleanly.
- WireMock 1.58 is very likely too old to be trusted on the final target.
- Gradle 9’s own Groovy/buildscript compatibility rules make it more important to keep build logic plain and modern.

### Most likely forced intermediate steps if one-shot fails
If the direct jump to the final target does not work, the most likely practical leap-frog sequence is:
1. **Modernize the Gradle build script syntax first** enough to run on a newer Gradle line.
2. **Move application/test dependencies to Groovy 4 + Spock 2.4-groovy-4.0** as a compatibility bridge if Groovy 5 breaks too much at once.
3. **Replace or upgrade blocking legacy libraries** (`http-builder-ng-apache`, WireMock) while still on the bridge stack.
4. **Advance from Groovy 4 to Groovy 5** once the dependency/test base is stable.
5. Land on **Java 25 / Gradle 9.6.1 / Groovy 5.0.6 / Spock 2.4-groovy-5.0** as the final state.

### Recommendation on delivery shape
- **Proposal target:** the final desired stack, not an intermediate stack.
- **Implementation expectation:** attempt the direct upgrade first.
- **Fallback:** explicitly permit an intermediate Groovy-4 bridge **inside the same modernization initiative** if the direct Groovy-5 jump is blocked.

That gives the user the preferred target while staying honest about the most likely migration pressure points.

## Fully-baked proposal direction
The plan should now say:
- the **ultimate target is fixed**, not exploratory:
  - Java 25 LTS
  - Gradle 9.6.1
  - Groovy 5.0.6
  - Spock 2.4-groovy-5.0
- the modernization should first try a **direct one-shot landing** on that stack
- the branch should include an explicit **decision gate** after the first build/test viability attempt:
  - if the direct target works with manageable fixes, continue straight through
  - if blocked by Groovy-5 or legacy-library incompatibilities, pivot to a **Groovy 4 bridge step** without changing the ultimate target
- `http-builder-ng-apache` should be treated as a probable replacement candidate, not a guaranteed survivor
- WireMock 1.58 should be treated as a probable required test-stack migration, with WireMock 3 as the more future-facing line than ancient 2.x

## Proposed next OpenSpec action
Update the current modernization proposal/design/tasks so they explicitly target:
1. Java 25 LTS
2. Gradle 9.6.1
3. Groovy 5.0.6
4. Spock 2.4-groovy-5.0
5. a direct one-shot attempt first
6. a documented Groovy-4 bridge fallback only if the direct target fails
7. early validation/replacement decisions for `http-builder-ng-apache`
8. early validation/migration decisions for WireMock

Also add explicit tasks to:
- prove whether the final target can build directly
- document the exact blocker if a bridge step is needed
- decide whether the HTTP client remains or must be replaced
- decide whether WireMock must jump to 3.x or whether another testing approach is cleaner

## Evidence captured during exploration
- `TODO.md` item 2 is the first incomplete item.
- `build.gradle` shows the legacy dependency/configuration model.
- `gradle/wrapper/gradle-wrapper.properties` pins `gradle-4.2.1-bin.zip`.
- `openspec/config.yaml` documents Java 8 / Groovy 2.4.x / Gradle 4.2.1.
- `src/main/groovy/RecordAllowance.groovy` and `src/test/groovy/*.groovy` confirm the app is Groovy-only with Spock/WireMock tests.
- Verification on the current baseline succeeded with:
  - `export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 && ./gradlew test`
- Upstream compatibility evidence gathered live:
  - Gradle current compatibility matrix for Java 25 support floors and Gradle 9.x context
  - Gradle 9 upgrade notes showing Java 17+ runtime requirement and Groovy 4 embedded baseline
  - Groovy download page showing Groovy 5 as latest stable for JDK 11+
  - Maven metadata / POMs for `spock-core:2.4-groovy-5.0`, `groovy:5.0.6`, and `groovy-all:5.0.6`
  - Maven metadata / POMs for `http-builder-ng-apache` and WireMock old/new lines

## Questions to answer in the proposal/design revision
- Do we pin **Groovy 5.0.6** explicitly now, or allow the latest 5.0.x patch within implementation?
- If the direct jump fails, is the preferred bridge specifically **Groovy 4 + Spock 2.4-groovy-4.0**, or should the bridge be left implementation-defined?
- If `http-builder-ng-apache` fails, do we replace it within this change, or split HTTP-client replacement into a dependent follow-on change?
- Should WireMock 1.58 be proactively replaced during modernization, or only after it proves to be the blocker?
