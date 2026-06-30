## Context

`RecordAllowance.groovy` is currently both the CLI entry point and the home for most application behavior. The file mixes flag parsing, environment access, remote YNAB reads, account/category lookup, interest and allowance calculations, transaction generation, and bulk-post preparation. Even with the newer test suite in place, that concentration increases the effort required to understand a change and makes narrow unit tests depend on broad setup.

The repo is now on the Java 25 / Gradle 9 / Groovy 5 baseline, and the current test harness already proves that component seams can be exercised safely without live YNAB access. This refactor should use that foundation to improve internal structure while preserving the existing runtime contract: `YNAB_ACCESS_TOKEN`, `--date`, `--dry-run`, exact YNAB naming assumptions such as `Fiores`, `Allowance Escrow`, and `Allowance`, and the current generated transaction semantics.

Because this application can post real transactions when not in dry-run mode, the design must avoid behavior drift hidden inside a large rewrite. The safest path is an incremental extraction of responsibilities into small focused classes that keep `RecordAllowance` as a thin orchestration entry point. Justin also requested that this branch first strengthen transaction ordering/grouping assertions in the existing tests, then complete the full modular refactor on the same branch with separate commits.

## Goals / Non-Goals

**Goals:**
- Reduce the size and responsibility count of `RecordAllowance.groovy` by extracting focused collaborators.
- Start the structural extraction with calculation logic, then continue through YNAB lookup/repository access and transaction assembly on the same branch.
- Make calculation, lookup, and transaction-assembly behavior easier to test in isolation.
- Introduce small explicit value objects where they clarify transaction-oriented data flows better than loose Groovy Maps.
- Preserve current CLI flags, environment requirements, YNAB endpoint usage, and transaction semantics.
- Improve readability by giving major concerns explicit names and module boundaries.

**Non-Goals:**
- Externalizing kid/rate/account configuration into `config.yaml`.
- Renaming the project or rewriting open-source onboarding docs as the main objective of this change.
- Adding new business behavior such as parent/child budget syncing.
- Replacing the current YNAB API contract, bulk-post workflow, or dry-run safety model.
- Performing a wholesale architectural rewrite or introducing a framework-heavy dependency.

## Decisions

### Decision: Start extraction with calculation logic, then move repository/lookup access, then transaction assembly
The implementation should first isolate deterministic allowance/interest calculation logic because it is the easiest seam to protect with focused tests and the least coupled to external side effects. After that, the branch should extract YNAB lookup/repository access and finally transaction assembly, with separate commits so the progression remains reviewable.

**Why this order?**
- Calculation logic is the most deterministic and easiest to lock down with pre-refactor assertions.
- It reduces the risk of behavior drift before side-effecting or payload-shaping code moves.
- It lets later repository and transaction-assembly extractions depend on clearer domain outputs instead of raw script-local logic.

**Alternative considered:** start with repository access or transaction assembly.
- Rejected because both are more entangled with external data shape and side-effect boundaries, making the first extraction riskier.

### Decision: Keep `RecordAllowance` as a thin entry point and extract focused domain/service classes
The refactor should preserve the existing entry point name and CLI surface while moving substantive logic into new classes. Likely seams include startup/CLI orchestration, YNAB repository-style access, category/account lookup helpers, allowance and interest calculation services, and transaction assembly helpers.

**Why this over a full rewrite?**
- It preserves the current invocation path and lowers migration risk.
- It allows incremental commits/tests while behavior remains stable.
- It fits the repo's small brownfield CLI character better than introducing a larger application framework.

**Alternative considered:** keep everything in one file and add more helper methods.
- Rejected because method-level extraction alone still leaves hidden shared state and weak module boundaries.

### Decision: Introduce small explicit value objects for transaction-oriented data
The refactor should replace the most opaque Map-shaped intermediate structures with small value objects where that improves readability and makes ordering/grouping assertions easier to express, while still serializing to the same YNAB JSON request shape at the boundary.

**Why this over staying with Maps everywhere?**
- It makes intent clearer during calculation and transaction-assembly steps.
- It reduces typo-prone implicit keys in intermediate logic.
- It supports stronger test assertions around transaction ordering, grouping, and field values.

**Alternative considered:** keep all intermediate payloads as Groovy-native Maps.
- Rejected because the user explicitly prefers small value objects for clarity and the refactor aims to improve readability, not just file size.

### Decision: Strengthen ordering/grouping assertions before moving production logic
Before the refactor begins, the existing tests should gain stronger assertions around transaction ordering and grouping so the branch captures the current behavior more precisely. That work should land in a separate commit before the structural refactor commits.

**Why this over deferring stronger assertions until after extraction?**
- It establishes a clearer behavioral contract before code motion starts.
- It reduces the chance that a refactor silently redefines ordering/grouping semantics.
- It creates a cleaner review trail showing test-hardening before implementation changes.

### Decision: Preserve hard-coded business rules in code during this change
The refactor should reorganize the code without changing where the domain rules live. Rates, child/account mappings, budget-name assumptions, and category-name conventions should remain code-defined unless a helper extraction is required to express the same rules more cleanly.

**Why this over combining refactor + config externalization?**
- Combining structural refactor with configuration redesign would enlarge the blast radius.
- TODO item 4 already captures externalization as a separate follow-on concern.
- Keeping rules in code isolates this change to structure/testability rather than behavior and operator workflow.

**Alternative considered:** move configuration into a new YAML file as part of the refactor.
- Rejected for this proposal because it changes operational setup and obscures whether regressions come from structure or new configuration loading.

### Decision: Expand tests around extracted collaborators while retaining end-to-end behavioral coverage
The test strategy should keep the current Spock and WireMock verification for observable behavior, but add or reshape targeted specs around extracted units so core calculations and lookup logic can be exercised without broad orchestration setup.

**Why this over relying only on existing end-to-end specs?**
- Narrow tests make the refactor safer and easier to maintain.
- They provide faster feedback when a small rule or lookup contract changes.
- They let the codebase benefit from modularity rather than only look modular.

**Alternative considered:** do a pure structural refactor with no test reshaping.
- Rejected because the stated goal includes testability, and unchanged test seams would undercut that value.

### Decision: Keep YNAB side effects behind explicit orchestration boundaries
Any extracted components should return calculated values or transaction payloads rather than performing hidden posts themselves. Actual bulk posting and dry-run decisions should remain explicit in the top-level execution flow.

**Why this matters?**
- It limits accidental side effects while refactoring a money-moving script.
- It makes dry-run behavior easier to reason about and preserve.
- It aligns with safer unit testing and more explicit integration coverage.

## Risks / Trade-offs

- **[Risk] Structural refactors may accidentally change transaction ordering or grouping** → Mitigation: strengthen ordering/grouping assertions before refactoring, keep end-to-end tests, and compare behavior through `./gradlew test` before apply completion.
- **[Risk] Extracting collaborators may duplicate domain data temporarily or create awkward interfaces** → Mitigation: favor small incremental seams first and accept minor transitional duplication if it improves clarity without changing behavior.
- **[Risk] Introducing value objects may add conversion code at the YNAB boundary** → Mitigation: keep value objects small, purpose-built, and serialized to Maps only at the API edge.
- **[Risk] Groovy dynamic behavior can hide broken wiring until runtime** → Mitigation: keep constructor/method boundaries explicit and add focused specs for extracted classes.
- **[Risk] Scope creep into configuration redesign or feature work** → Mitigation: treat externalized config, onboarding, and syncing as explicit non-goals for this change.

## Migration Plan

1. Strengthen transaction ordering/grouping assertions in the existing tests and commit that safety-net work first.
2. Extract calculation logic into focused collaborators and verify tests stay green.
3. Extract YNAB repository/lookup access behind clearer service boundaries and verify tests stay green.
4. Extract transaction assembly into focused helpers/value objects while preserving the YNAB bulk-post request shape.
5. Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test` after each major stage and again at the end.
6. If structure changes affect developer understanding, update `README.md` with concise notes about the major module boundaries.
7. Use dry-run-safe verification only; do not introduce any verification step that posts real YNAB transactions.

Rollback would be a normal git revert of the refactor commits, because no persisted data format or external configuration contract should change in this proposal.

## Open Questions

- Should the value-object layer stop at transaction and lookup data, or are there additional domain concepts that become obviously clearer once the calculation seam is extracted?
- Are there any remaining implicit ordering assumptions in the current tests that need to be made explicit once the pre-refactor assertion pass is complete?