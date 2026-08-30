## Why

The disposable-plan QA campaign can already run without YNAB UI for most A–D scenarios, but that path is local-only (`./gradlew qaAutomated`). Reviewers cannot opt a PR into live end-to-end proof on GitHub. We need a guarded Actions job that runs only the fully automated suite, never writes tokens to disk, and surfaces each scenario as a JUnit result on the PR.

## What Changes

- Add a **separate** GitHub Actions job (not part of `build-test`) that runs the full `qaAutomated` suite against the four disposable QA plans.
- Gate that job so it runs only on pull requests in `justinfiore/ynab-bank-of-dad` when the PR has label `end-to-end-qa` **and** the author is `justinfiore` or `jhorgenson`. Forks never run it.
- Emit one JUnit testcase per automated scenario. Upload JUnit XML and publish suite/case results in the PR check UI. Any FAIL or unexpected BLOCKED fails the job.
- Change `qa/run_qa_suite.py` to load tokens from environment variables only. Do not require, create, or write `tokens.txt`.
- Change QA config loading so `qa/config/qa-sync.yaml` can resolve plan `fullId` values from env vars (no token material in any generated file).
- Document required GitHub secrets and the opt-in label. Do **not** run `qaManual` (A8/B5/C8) in Actions.
- Make the shared QA HTTP client resume every method after 429 responses until success, using resume headers or capped linear backoff, and pace cleanup requests through `QA_CLEANUP_PACING_MS`.

## Capabilities

### New Capabilities

- `disposable-qa-github-actions`: Opt-in PR end-to-end job for UI-free disposable-plan QA, env-only secrets, JUnit reporting, and fail-closed gating.

### Modified Capabilities

- `github-actions-ci`: Default `build-test` stays token-free. The new live QA job is additional, labeled, author- and fork-gated, and never runs on ordinary push/PR verification.

## Impact

- `.github/workflows/` (new workflow or new job)
- `.github/scripts/publish_test_summary.py` if JUnit output is reused
- `qa/run_qa_suite.py` token loading
- `qa/lib/` config loaders that currently require a local `qa/config/qa-sync.yaml` with inline UUIDs
- `qa/config/qa-sync.yaml.example`, `qa/SETUP.md`, `qa/README.md`, `AGENTS.md`
- Offline unit tests for env-only tokens and env-substituted plan IDs
- GitHub repository secrets (tokens + plan UUIDs). No family-budget secrets.
