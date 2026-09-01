## Context

`qaAutomated` already covers the UI-free disposable-plan matrix (A1–A7, B1–B4, C1–C7, C9, D1–D4). `qaManual` still needs a human for Move Money and split edits and stays out of CI.

Today `qa/run_qa_suite.py` exits if repo-root `tokens.txt` is missing, even when `PARENT_ACCESS_TOKEN` and the three child token env vars are set. `qa/run_live_campaign.py` and discovery load `qa/config/qa-sync.yaml` with inline `fullId` UUIDs. That file is gitignored. GitHub Actions must not write tokens to disk.

Default CI (`build-test`) runs `./gradlew testAll` and `installDist` with no YNAB token. That job must stay unchanged in trigger scope.

This change adds an opt-in live job against the four disposable plans only: `Jorsten's Plan`, `Jorsten Jr's Plan`, `Borsten's Plan`, `Thorsten's Plan`.

## Goals / Non-Goals

**Goals:**

- Run the full set of UI-free automated disposable-plan scenarios on GitHub Actions.
- Trigger only on pull_request events when label `end-to-end-qa` is present.
- Allow only authors `justinfiore` and `jhorgenson`.
- Refuse fork PRs (`head.repo.full_name` must equal `github.repository`).
- Use env vars / Actions secrets only; never write token values to the runner filesystem.
- Resolve plan UUIDs from env into the existing yaml shape without embedding tokens.
- Publish JUnit XML so each scenario is visible in the PR check UI; fail the job on failure.
- Keep `qaAutomated` / `qaManual` off `test`, `testAll`, `check`, `build`, and `installDist`.

**Non-Goals:**

- Running A8, B5, C8, or C8b in Actions.
- Running live QA on every PR or on push to `master`.
- Writing `tokens.txt` from secrets as a workaround.
- Using family-budget tokens or names.
- Adding unpinned third-party test-reporter actions.

## Decisions

### Separate workflow/job

Add `.github/workflows/end-to-end-qa.yml` (or an equivalent isolated job). Do not add live YNAB steps to `ci.yml` `build-test`.

Triggers: `pull_request` types `opened`, `synchronize`, `reopened`, `labeled`, `unlabeled` so adding the label later starts the job.

Job-level `if` (all required):

1. `github.event.pull_request.head.repo.full_name == github.repository`
2. `contains(github.event.pull_request.labels.*.name, 'end-to-end-qa')`
3. `github.event.pull_request.user.login == 'justinfiore' || github.event.pull_request.user.login == 'jhorgenson'`

Unlabeled or fork PRs skip the job (or never start it). Removing the label should not keep a new run going; in-flight runs may finish.

### Secrets (env only)

Required Actions secrets, mapped to env (never `echo`ed, never written to files):

| Secret / env | Purpose |
|---|---|
| `PARENT_ACCESS_TOKEN` | Parent disposable plan |
| `JORSTEN_JR_ACCESS_TOKEN` | `Jorsten Jr's Plan` |
| `BORSTEN_ACCESS_TOKEN` | `Borsten's Plan` |
| `THORSTEN_ACCESS_TOKEN` | `Thorsten's Plan` |
| `QA_PARENT_PLAN_ID` | Full immutable UUID for `Jorsten's Plan` |
| `QA_JORSTEN_JR_PLAN_ID` | Full immutable UUID for `Jorsten Jr's Plan` |
| `QA_BORSTEN_PLAN_ID` | Full immutable UUID for `Borsten's Plan` |
| `QA_THORSTEN_PLAN_ID` | Full immutable UUID for `Thorsten's Plan` |

`QA_CONFIRM_LIVE_MUTATIONS=YES` is set only inside this job.

### `run_qa_suite.py`

Change `load_tokens()`:

- Prefer already-set environment variables for the four token names.
- Do **not** require `tokens.txt`.
- If `tokens.txt` exists locally, it may still fill **unset** env vars (local convenience). Never print, copy, or write token values.
- If any required token env var is empty after that, exit `BLOCKED` without creating a token file.

### `qa-sync.yaml` config

Keep the checked-in example at `qa/config/qa-sync.yaml.example`. Extend loading so each budget `fullId` may be:

- a complete UUID (local ignored file), or
- an env placeholder such as `${QA_PARENT_PLAN_ID}` / `$QA_PARENT_PLAN_ID`

Resolution happens in memory. Do not write a rendered yaml that contains resolved UUIDs next to logs that get uploaded, unless those artifacts are already redacted the same way live receipts are (no full UUIDs). Prefer keeping resolved IDs only in process memory and the existing allowlist objects.

CI may copy the **example** file to `qa/config/qa-sync.yaml` on the runner (placeholders only, no secrets) and let the loader expand env. Alternatively, accept `QA_SYNC_CONFIG` pointing at the example path. Do not generate a file that interpolates secret values onto disk.

Update `qa/run_live_campaign.py` / discovery loaders that currently `yaml.safe_load` the file and take `fullId` literally.

### JUnit and PR visibility

After `qaAutomated`, write JUnit XML under `build/test-results/qaAutomated/TEST-qaAutomated.xml` (or one file per scenario). Each automated `scenario_id` is a `testcase`.

Mapping:

- `PASS` → passed
- `FAIL` → `<failure>`
- unexpected `BLOCKED` / executor crash → `<failure>` or `<error>`
- Manual scenarios stay out of this file

The job fails if the XML contains failures/errors or if the runner exits non-zero.

Reuse the in-repo `.github/scripts/publish_test_summary.py` scan of `build/test-results/` so the check summary lists suite totals and failing names. Upload the JUnit directory as an artifact. Do not add a new unpinned third-party reporter. Pin any `uses:` to a full SHA, matching `github-actions-ci`.

GitHub’s native JUnit annotation path, if used, must stay SHA-pinned and must not log secrets.

### A1 inside Actions

`qaAutomated` currently calls `_baseline()`, which can run `./gradlew testAll --rerun-tasks`. That is redundant and too slow next to `build-test`. When `GITHUB_ACTIONS=true` (or `--skip-gradle-baseline`), A1 records the Python harness only, not a nested `testAll`.

### Concurrency, rate limits, and cleanup pacing

Use a workflow `concurrency` group so two labeled PRs do not mutate the same disposable plans at once (`cancel-in-progress: false` preferred so an in-flight campaign is not killed mid-mutation). The job timeout is 360 minutes.

The shared QA client retries 429 for all methods until success by default, while retaining an explicit finite retry override for unit tests. It derives a wait from case-insensitive `Retry-After` integer/HTTP-date values and common reset-epoch headers. If none is usable, it waits linearly in five-second increments. A single wait is capped at YNAB's one-hour window. No other 4xx is retried, and errors and telemetry retain no headers, URLs, tokens, or plan IDs.

Cleanup temporarily enables client-level request pacing for every cleanup HTTP request, using `QA_CLEANUP_PACING_MS` with a 500 ms default and `0` to disable. Invalid values fail closed before cleanup discovery. Pacing is disabled after the cleanup window.

### Safety

- Allowlist remains exact display name + full UUID.
- Family plan names never appear in the job config.
- Logs/artifacts go through the existing redaction/secret scan.
- `qaManual` is not invoked.

## Risks / Trade-offs

- Shared disposable plans: only one live campaign at a time; queued PRs wait.
- Sustained YNAB rate limiting can make a labeled PR take hours, but the campaign resumes rather than failing solely because of HTTP 429.
- Plan UUIDs in GitHub secrets are still secrets; never print them.

## Migration

Repository admins create the eight secrets and the `end-to-end-qa` label. Existing local `tokens.txt` / ignored yaml keep working for laptops. No production syncer behavior changes.

## Open Questions

None. Author, label, fork, JUnit, env-only tokens, and automated-only scope were specified by the requester.

## Amendment: failed full-campaign remediation

Actions run `33313572332` exposed five coupled reliability gaps. Live observations and assertions must be isolated by the exact `<campaign>:<scenario>` memo tag and omit deleted transactions unless deletion evidence is the subject of the scenario. In particular, C1 must retain the captured child transaction identity and compare its expected fields instead of deriving success from campaign-wide counts.

The production Groovy HTTP client, not only the Python QA helper, must resume an explicitly rejected 429 request for every HTTP method. Retry timing is injectable and header-driven, falls back to capped linear waits, and reports only method, resource class, and status. Retrying a write after 429 reuses the same request and therefore preserves the existing manifest, import identity, idempotency, and reconciliation recovery model.

The automated orchestrator treats each independent scenario as a fault boundary. Executor exceptions create error receipts and execution continues; B2 alone is dependency-blocked by a failed B1. Tagged-only cleanup runs in `finally` between independent scenarios and at finalization. Cleanup writes its verification manifest before returning, and any verification result other than `PASS` raises a safe error so the responsible receipt records `cleanup_failure` and the campaign is gated. Final metadata and JUnit generation run even after unexpected exceptions.

JUnit distinguishes assertion failures, executor errors, dependency skips, and safety blocks. Missing or dependency-blocked cases are skipped in XML, but the campaign remains incomplete and exits nonzero unless the only skipped descendants are explained by an antecedent failure/error. All timing fields are finite numeric values.

CI evidence is packaged from the deterministic, repo-relative `build/qa/current-campaign.json` pointer after the live step under `always()`. The pointer contains only schema version, safe campaign ID, and `qa/artifacts/<campaign>` source path. The separate packager accepts partial campaigns without a zero-write or complete-matrix gate. It copies regular files only from that exact artifact tree, ignores symlinks, sanitizes text with the existing token/Authorization/full-UUID routines, and byte-scans every delivered file before publication. Existing SQLite JSON audit exports are retained; raw SQLite state is never read into the bundle.

The delivered `ci-evidence-manifest.json` records schema, campaign, branch/commit, derived `PASS`/`FAIL`/`PARTIAL` status and completeness, receipt counts, cleanup verification, `source_type=qa-artifacts-copy`, `raw_state_included=false`, stable copy-exclusion rules, and safety scan results. Selected completeness requires every selected scenario receipt and excludes `NOT_RUN` or dependency-blocked receipts; failure/error receipts may still form a complete matrix but derive `FAIL`. Cleanup `FAIL` also derives `FAIL`; absent or non-PASS cleanup is `PARTIAL` unless another selected failure/error already requires `FAIL`. Only a complete all-PASS selected matrix with cleanup `PASS` derives `PASS`, and extra manual receipts do not affect that decision. `SHA256SUMS` covers every regular campaign-directory file recursively except `SHA256SUMS` itself. The sibling ZIP includes the checksum listing, and the sibling `.zip.sha256` covers the ZIP. Actions uploads only `build/qa-ci-evidence/` with seven-day retention; it never uploads raw `qa/artifacts/`.
