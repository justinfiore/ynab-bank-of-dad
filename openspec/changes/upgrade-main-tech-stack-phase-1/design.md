## Context

The repository currently builds as a small single-script Groovy CLI on a tightly coupled legacy stack: Java 8, Gradle 4.2.1, Groovy 2.4.15, and Spock 1.3 for tests. `build.gradle` still uses deprecated Gradle configurations such as `compile` and `testCompile`, plus legacy application-plugin syntax with top-level `mainClassName`, so a wrapper upgrade cannot be treated as a version bump alone.

The target is now explicit. Additional research plus Justin’s direction establish the desired final baseline as Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4 with the Groovy 5 variant. Upstream compatibility data supports this as a legitimate end state: Gradle 9.1.0+ supports Java 25, Gradle 9.6.1 is current, Groovy 5 is the latest stable line for JDK 11+, and `spock-core:2.4-groovy-5.0` is published.

This is still a brownfield script-oriented project. The design therefore favors the smallest practical modernization that gets the build, tests, and documentation onto the researched final baseline without mixing in unrelated architecture work or configuration externalization from later TODO items. The preferred path is a direct attempt at the final target, but the design now explicitly acknowledges that a Groovy-4 bridge may be the least-painful fallback if the leap from Groovy 2.4 to 5.0 proves too large in one move.

## Goals / Non-Goals

**Goals:**
- Move the project to the final supported baseline of Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4-groovy-5.0.
- Replace deprecated Gradle DSL usage with current supported equivalents required by Gradle 9.
- Preserve the current runtime contract: `YNAB_ACCESS_TOKEN`, CLI flags, category/account naming assumptions, budget-selection behavior, and dry-run-safe execution patterns.
- Keep `./gradlew test` as the primary verification path and update docs/config accordingly.
- Validate and, if necessary, replace only the legacy dependencies that block a successful landing on the target stack.

**Non-Goals:**
- Re-architecting `RecordAllowance` into a multi-module or service-oriented design.
- Externalizing business rules or family-specific configuration; that belongs to the later configuration/portability TODO item.
- Intentionally changing YNAB transaction-generation semantics, endpoint usage, or category/account names.
- Treating an intermediate bridge stack as the new official end state.
- Performing broad opportunistic library modernization beyond what is necessary to reach and verify the target stack.

## Decisions

### Decision: Make Java 25 / Gradle 9.6.1 / Groovy 5.0.6 / Spock 2.4-groovy-5.0 the explicit final target
The proposal should now pin the desired destination instead of speaking about a generic “modern supported baseline.” This keeps the work aligned with Justin’s stated intent and prevents the plan from drifting toward a merely easier but already-obsolete intermediate target.

Alternatives considered:
- Keep the plan open-ended and choose exact versions during implementation. Rejected because the user has already specified the desired destination and upstream evidence supports it.
- Stop at Java 21 / Groovy 4. Rejected because that would no longer match the requested target.

### Decision: Attempt a direct one-shot landing first
The branch should first try to land directly on the final target stack. The codebase is small enough that a direct attempt is justified, and doing so gives the fastest path to the desired outcome if the legacy dependencies cooperate.

Alternatives considered:
- Require an intermediate bridge up front. Rejected because it adds churn before we know the direct attempt actually fails.

### Decision: Allow a Groovy-4 bridge fallback if the direct Groovy-5 jump is blocked
If the direct target fails due to Groovy-major migration pain or legacy ecosystem incompatibility, the preferred fallback is a temporary bridge through Groovy 4 with Spock 2.4-groovy-4.0 while keeping Java 25 / Gradle 9.6.1 as the intended direction when possible. This is the most natural stepping stone because Groovy 4 already absorbs the coordinate migration and several ecosystem transitions from the Groovy 2/3 era.

Alternatives considered:
- Use a Java 17 / Gradle 8 / Groovy 4 full interim supported baseline. Rejected as a default because it adds an extra public target the user does not want unless necessary.
- Jump directly from Groovy 2.4 to a long-lived bridge on Groovy 3. Rejected because Groovy 4 is the more useful modernization bridge for today’s ecosystem.

### Decision: Replace `http-builder-ng-apache` with an in-repo wrapper over JDK `HttpClient`
`http-builder-ng-apache` is archived, lightly maintained, and now proven incompatible with this repo's Groovy 5 migration path. The replacement should be a small in-repo wrapper over the JDK `java.net.http.HttpClient` rather than a new third-party client dependency. The wrapper should stay focused on the project's actual YNAB API needs, be easy to understand for future maintainers, and be documented well enough that the project does not simply trade one opaque client abstraction for another.

Alternatives considered:
- Assume the HTTP client will survive unchanged. Rejected because the dependency’s age made that too optimistic and the direct attempt already produced a real Groovy-5 failure.
- Replace it with another third-party client such as OkHttp. Rejected because the ergonomic benefits would still require a thin wrapper in this repo, so adding another dependency would not buy enough to justify the extra maintenance surface.
- Force an HTTP client rewrite before any migration attempt. Rejected as an initial strategy, but the migration has now produced enough evidence that the rewrite is in scope and necessary.

### Decision: Keep WireMock-based integration tests while replacing only the HTTP transport layer
The project should continue to use WireMock-style integration tests to prove real request/response behavior against a simulated YNAB API. The current modernization work should replace only the production HTTP transport layer, not abandon the integration-test strategy that already fits the repo well.

Alternatives considered:
- Proactively rewrite all integration tests away from WireMock. Rejected because the user explicitly wants to keep WireMock integration coverage and the current issue is the client library, not the value of end-to-end HTTP stubbing.

### Decision: Keep build logic simple and Gradle-9-friendly
Gradle 9 itself runs with its own compatibility constraints and expects modern DSL usage. The migration should keep the build script plain, avoid fancy custom Groovy build logic, and use supported Gradle 9 idioms so the build system does not become an accidental second migration project.

Alternatives considered:
- Preserve legacy Gradle script idioms as long as possible. Rejected because they are part of the current problem.

### Decision: Update documentation and OpenSpec context as part of the same change
`README.md`, `AGENTS.md`, and `openspec/config.yaml` currently describe Java 8 / Gradle 4 behavior as if it were the supported baseline. Those documents must be updated in the same change so the repo's instructions match the actual validated toolchain.

Alternatives considered:
- Deferring docs updates until after apply. Rejected because it leaves the repo in a misleading state immediately after the stack change lands.

## Risks / Trade-offs

- [Direct Groovy 2.4 → 5.0 jump is too disruptive] → Mitigation: attempt it first, but pivot to a Groovy-4 bridge without changing the ultimate target.
- [Legacy HTTP client fails on the modern stack] → Mitigation: replace `http-builder-ng-apache` with a small in-repo JDK `HttpClient` wrapper and verify both wrapper-level and WireMock-backed integration behavior.
- [WireMock 1.58 fails on the modern stack] → Mitigation: upgrade or replace the test harness as part of the modernization rather than preserving an obsolete dependency indefinitely.
- [Gradle 9 wrapper upgrade exposes multiple DSL and plugin breakages at once] → Mitigation: migrate the build script in lockstep with the wrapper change and use the existing automated tests as the regression net.
- [Modernization accidentally changes runtime behavior] → Mitigation: treat runtime semantics as preserved constraints and verify with `./gradlew test` plus a dry-run-safe invocation path if the new stack reaches runnable state.
- [Documentation drift after changing the supported JDK baseline] → Mitigation: make docs/config updates a first-class task, not a follow-up.

## Migration Plan

1. Pin the final target versions in the change artifacts: Java 25 LTS, Gradle 9.6.1, Groovy 5.0.6, and Spock 2.4-groovy-5.0.
2. Update the Gradle wrapper and migrate `build.gradle` to Gradle-9-compatible dependency configurations and application-plugin syntax.
3. Migrate Groovy dependencies from `org.codehaus.groovy` to `org.apache.groovy` and align the test stack with Spock 2.4-groovy-5.0.
4. Attempt a direct build/test run on the final target stack and capture the exact blockers.
5. Replace `http-builder-ng-apache` with a small, documented in-repo wrapper over JDK `HttpClient`, keeping the wrapper focused on the YNAB API calls this script actually needs.
6. Add direct tests for the wrapper plus WireMock-backed integration coverage proving the migrated client still sends the expected requests and parses responses correctly.
7. If the direct attempt is blocked by Groovy-major or ecosystem issues beyond the HTTP client, introduce the smallest useful bridge step—preferably Groovy 4 / Spock 2.4-groovy-4.0—while keeping the final target unchanged.
8. Run build verification such as `./gradlew test`, `./gradlew tasks --all`, and `./gradlew installDist`, plus a dry-run-safe application invocation if the runtime remains buildable.
9. Update `README.md`, `AGENTS.md`, and `openspec/config.yaml` to reflect the new verified commands, prerequisites, and the rationale for using the in-repo JDK `HttpClient` wrapper.
10. Preserve a rollback path by keeping the modernization isolated to one branch/change so it can be reverted cleanly if needed.

## Open Questions

- Should the proposal pin Groovy 5.0.6 exactly, or allow the latest 5.0.x patch during implementation if a newer patch appears before apply?
- If a bridge is needed, should the plan explicitly prefer Groovy 4 / Spock 2.4-groovy-4.0, or leave the exact bridge stack implementation-defined?
- What is the smallest wrapper surface that keeps the YNAB integration readable without recreating an overly clever internal DSL?

## Implementation Notes

### Direct final-target attempt results so far
The apply work has already validated several parts of the final-target path on this branch:
- Gradle wrapper successfully upgraded to **9.6.1**.
- `build.gradle` was modernized from `apply plugin`, `compile`, `testCompile`, and top-level `mainClassName` to the Gradle 9 plugin/configuration model.
- The project now compiles on **Java 25** using `/usr/lib/jvm/java-25-openjdk-amd64`.
- The non-WireMock Spock suite currently passes on the Java 25 / Gradle 9.6.1 / Groovy 5 / Spock 2.4 path.

### Confirmed blocker
The direct final-target path is currently blocked by the HTTP client / WireMock integration path, not by the overall Gradle 9 or Java 25 migration. The concrete failure seen during `./gradlew test` is `http-builder-ng` initialization failing under Groovy 5 with `NoClassDefFoundError: groovy/util/slurpersupport/GPathResult` during `HttpBuilder.configure(...)` calls used by the WireMock-backed tests.

This means:
- the direct target is **partially viable** already,
- but `http-builder-ng` is not yet proven compatible with the Groovy 5 runtime in this repo,
- and the remaining work is now sharply focused on the HTTP stack rather than the general build/toolchain migration.

### Chosen resolution path
Based on the follow-up decision, the migration should not try to rescue `http-builder-ng` and should not add another third-party HTTP dependency just to recover ergonomics. Instead it should:
1. implement a small, readable wrapper around JDK `HttpClient`,
2. document why that choice was made (no extra dependency, stable JDK support, wrapper needed either way, easier long-term maintenance),
3. add focused tests for the wrapper,
4. keep WireMock-backed integration tests to prove the real YNAB request/response flow,
5. then re-run the full Gradle verification path before updating repo docs.

At this point, the HTTP client decision is no longer open: the chosen direction is an in-repo JDK `HttpClient` wrapper with WireMock retained for integration testing.
