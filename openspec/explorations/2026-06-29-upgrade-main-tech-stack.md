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

## Main modernization risks
1. **Gradle DSL breakage**
   - Moving from Gradle 4 to a current major release will require replacing deprecated configurations such as `compile` and `testCompile`.
   - The legacy `mainClassName` style will likely need to become the current `application { mainClass = ... }` style.

2. **Groovy/Spock compatibility coupling**
   - Current tests are tied specifically to `spock-core:1.3-groovy-2.4`.
   - Upgrading Java and Gradle without moving Groovy/Spock together is likely to leave the build in a half-broken state.

3. **Library age and Java compatibility**
   - `slf4j-api:1.6.1`, `logback-classic:1.2.3`, `commons-lang3:3.7`, and `wiremock:1.58` are all old enough that some should be refreshed during the same change.
   - The biggest unknown is `http-builder-ng-apache:1.0.3`: the app depends on it for YNAB calls, so its compatibility with a current Groovy/Gradle/Java stack must be validated early.

4. **Operational documentation drift**
   - `AGENTS.md`, `README.md`, and `openspec/config.yaml` all encode the current Java 8/Gradle 4 assumptions and would become wrong immediately after a successful upgrade.

## Candidate approaches

### Option A — One-shot full modernization in a single change
Upgrade Java, Gradle, Groovy, Spock, and the outdated libraries together.

**Pros**
- Reaches the desired end state fastest.
- Avoids landing an intermediate stack that still requires near-term rework.
- Lets docs and tests converge once.

**Cons**
- Highest risk of build breakage because every compatibility axis moves at once.
- Harder to isolate whether failures are due to Gradle DSL changes, Groovy runtime changes, or individual dependency incompatibilities.
- If `http-builder-ng-apache` or WireMock prove problematic, the whole change can stall.

### Option B — Two-phase modernization
Phase 1 modernizes the build/toolchain scaffolding just enough to get onto a newer supported Gradle/Groovy/Spock baseline. Phase 2 refreshes remaining libraries and cleans up docs/runtime validation.

**Pros**
- Lower debugging complexity than a one-shot jump.
- Preserves momentum if one legacy library becomes the long pole.
- Easier to review and verify with existing test coverage.

**Cons**
- Requires two linked changes/specs instead of one.
- Temporary intermediate state may still contain some older dependencies.

### Option C — Minimal Java/Gradle uplift only
Upgrade Java and Gradle while keeping Groovy and most dependencies as close to current as possible.

**Pros**
- Smaller apparent scope.

**Cons**
- Likely unrealistic because Groovy 2.4 + Spock 1.3 are tightly coupled to the old build stack.
- Risks spending effort on a transition state that still cannot support current tooling cleanly.
- Does not really satisfy TODO item 2’s full intent.

## Recommended direction
**Recommend Option B: a staged modernization, beginning with a build-viability change.**

Reasoning:
- The repo is small, but the stack layers are strongly coupled.
- Existing automated tests now provide a safe regression net, so the next logical step is to use them while moving the build onto a modern supported baseline.
- A staged plan gives us room to discover whether `http-builder-ng-apache` can remain in place or must be replaced, without blocking the entire effort.

## Proposed next OpenSpec action
Create a change that targets **phase 1 of the modernization** with scope like:
1. upgrade the Gradle wrapper to a current supported version
2. migrate `build.gradle` off deprecated configurations and application-plugin syntax
3. upgrade Groovy + Spock to a compatible supported pair
4. make the test suite pass on the newer toolchain
5. update repo docs/config that hard-code Java 8 / Gradle 4 assumptions

Leave phase-2 dependency refreshes (or any HTTP client replacement) as either explicit follow-on tasks within the same proposal if proven low-risk, or as a second change if compatibility work expands.

## Evidence captured during exploration
- `TODO.md` item 2 is the first incomplete item.
- `build.gradle` shows the legacy dependency/configuration model.
- `gradle/wrapper/gradle-wrapper.properties` pins `gradle-4.2.1-bin.zip`.
- `openspec/config.yaml` documents Java 8 / Groovy 2.4.x / Gradle 4.2.1.
- `src/main/groovy/RecordAllowance.groovy` and `src/test/groovy/*.groovy` confirm the app is Groovy-only with Spock/WireMock tests.
- Verification on the current baseline succeeded with:
  - `export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 && ./gradlew test`

## Questions to answer in the subsequent proposal/design
- What exact target baseline should phase 1 use (latest Java LTS plus a Groovy/Spock pair known to support it)?
- Can `http-builder-ng-apache` stay, or does it become the forcing function for an HTTP client replacement?
- Should phase 1 preserve runtime compatibility with older JDKs, or is the intent to require the new LTS JDK immediately?
- Are the checked-in Windows scripts part of the supported surface for this change, or can they wait for TODO item 3?
