# YNAB Bank of Dad
Small Gradle + Groovy CLI for Bank of Dad workflows on YNAB.

## Git Workflow
- Work on `master` directly only after pulling the latest remote changes.
- On any other branch, push commits to the remote.
- When a branch is ready, open a GitHub PR with `gh` and request `justinfiore` as reviewer.
- Doc or workflow updates to this file should be committed and pushed to `master`.

## Repo Conventions
- Main entry point: `ynabbankofdad.allowance.RecordAllowance`.
- Sync entry point: `ynabbankofdad.sync.ParentChildBudgetSyncer`.
- The app talks to the YNAB REST API through the local `YnabHttpClient` wrapper.
- Do not guess YNAB API contracts, request bodies, or response shapes. Check the official docs first.
- Never log raw secrets such as `YNAB_ACCESS_TOKEN`.

## Change Discipline
- Keep helper scripts repo-relative and environment-driven.
- Preserve configured YNAB names unless a change explicitly updates config and lookup logic together.
- Use `--dry-run` first for changes that could create transactions.
- Non-doc changes should include/update tests and pass `./gradlew testAll` before being considered done.
- Before committing, make sure the relevant Gradle tests are passing.
  - Typical commands are
    - Unit Tests: `./gradlew test`
    - Integration Tests: `./gradlew integrationTest`
    - All Tests: `./gradlew testAll`.
- Disposable YNAB QA campaigns are **not** part of `test`, `testAll`, `check`, `build`, or `installDist`. Invoke them explicitly:
  - `./gradlew qaAutomatedSmoke -PqaConfirmLive=YES` — bounded, rate-budgeted live smoke scenarios A2, A3, B1, and B2. This is the UI-free PR gate: it proves read-only preflight, one tagged fixture lifecycle, replay/idempotency, and tagged-only cleanup against the four disposable QA plans.
  - `./gradlew qaAutomated -PqaConfirmLive=YES` — the complete UI-free campaign (A1–A7, B1–B4, C1–C7, C9, D1–D4). Run it only as an explicit, rate-budgeted controlled validation; it is not the default PR workflow.
  - `./gradlew qaManual -PqaConfirmLive=YES` — prepares A8/B5 Move Money funding and the C8b remain-a-split fixture, then prints UI steps. After those UI edits: `./gradlew qaManual -PqaConfirmLive=YES -PqaManualReady=YES`.
- Setup for those suites (plans, categories, tokens) is in `qa/SETUP.md`. Never commit `tokens.txt` or `qa/config/qa-sync.yaml`.
- Opt-in GitHub Actions live QA runs `qaAutomated` only on pull requests that have the `end-to-end-qa` label, are authored by `justinfiore` or `jhorgenson`, and are not from a fork. Tokens and plan UUIDs come from repository secrets / env vars and must never be written to disk. Manual A8/B5/C8 stays local.
