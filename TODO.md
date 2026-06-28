# TODO

High-level prioritized work items for this repo. These are intentionally lightweight placeholders that can later be expanded into OpenSpec changes/specs. Keep this list ordered by priority and check items off when they are complete.

1. [ ] Add unit and integration tests
   - Evaluate current, well-supported Groovy BDD-style testing frameworks that are compatible with the repo’s current Groovy and Java versions.
   - Select a unit testing framework and mocking framework.
   - Determine whether integration tests should use a mock HTTP layer and assess tools such as WireMock/OpenWire for simulating the YNAB API.
   - Add coverage for core calculation logic and YNAB API interaction paths.

2. [ ] Upgrade the main tech stack
   - Upgrade Java to the latest LTS release.
   - Upgrade Gradle to the latest major release.
   - Replace deprecated or incompatible libraries.
   - Upgrade the chosen testing framework(s) to current supported versions.
   - Verify Groovy compatibility across the upgraded toolchain.

3. [ ] Externalize configuration and improve script portability
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
