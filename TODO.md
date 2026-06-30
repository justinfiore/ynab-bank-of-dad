# TODO

High-level prioritized work items for this repo. These are intentionally lightweight placeholders that can later be expanded into OpenSpec changes/specs. Keep this list ordered by priority and check items off when they are complete.

1. [x] Add unit and integration tests
   - Completed on branch `opsx-explore-tests-plan` via archived OpenSpec change `2026-06-28-add-automated-tests`.
   - Added Spock 1.3 (`groovy-2.4`) unit tests and WireMock 1.58 integration-style tests compatible with the current Groovy 2.4 / Java 8 stack.
   - Added coverage for core calculation logic, budget/account/category lookup, and bulk transaction posting behavior.
   - Configured Gradle test runs to emit both JUnit XML and HTML reports, and documented the required `./gradlew test` workflow.

2. [x] Upgrade the main tech stack
   - Upgrade Java to the latest LTS release.
   - Upgrade Gradle to the latest major release.
   - Replace deprecated or incompatible libraries.
   - Upgrade the chosen testing framework(s) to current supported versions.
   - Verify Groovy compatibility across the upgraded toolchain.

3. [x] Refactor the codebase for modularity, readability, and testability
   - Break up large or tightly coupled classes/scripts into clearer modules with focused responsibilities.
   - Improve naming, boundaries, and internal structure so the code is easier to understand and maintain.
   - Reduce hidden dependencies and side effects to make behavior easier to test.
   - Expand or reshape tests alongside the refactor so the improved structure remains well covered.

4. [ ] Add GitHub Actions to build, run, and report on tests
   - Add CI workflow(s) that build the project and run the automated test suite on pushes and pull requests.
   - Publish or preserve test results/artifacts so failures are easier to inspect remotely.
   - Document the CI workflow and any required repository settings or badges.

5. [ ] Externalize configuration and improve script portability
   - Factor hard-coded configuration into `config.yaml` so the tool is reusable by others.
   - Add Bash scripts comparable to the existing Windows batch scripts.
   - Make the Windows batch scripts less specific to Justin’s local machine and environment.

4. [ ] Rename and improve open-source onboarding
   - Rename the project to `YNABBankOfDad` where appropriate (repo, docs, and code references as needed).
   - Rewrite `README.md` to better target an external open-source audience.
   - Add `QUICK_START.md` with concise setup and first-run instructions.

5. [ ] Support parent/child budget transaction syncing
   - Add support for syncing transactions between the parent YNAB budget and child YNAB budgets.
   - Define the expected sync model, mapping rules, and safety checks before implementation.

6. [ ] Add licensing and public-hosting readiness
   - Add a `LICENSE` file.
   - Move the Git repo to GitHub or another location where it can be shared publicly.
