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
- Before committing, make sure the relevant Gradle tests are passing. Typical commands are `./gradlew test`, `./gradlew integrationTest`, and `./gradlew testAll`.
