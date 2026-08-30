## 1. Token loading

- [x] 1.1 Change `qa/run_qa_suite.py` `load_tokens()` to accept the four token env vars without requiring `tokens.txt`
- [x] 1.2 Keep optional local `tokens.txt` fill-in for unset env vars only; never write or print token values
- [x] 1.3 Add unit tests for env-only success, missing-env block, and no file creation

## 2. qa-sync.yaml env IDs

- [x] 2.1 Add in-memory `${ENV}` / `$ENV` expansion for budget `fullId` values in the QA config loader used by discovery and `run_live_campaign.py`
- [x] 2.2 Update `qa/config/qa-sync.yaml.example` to document the four `QA_*_PLAN_ID` placeholders
- [x] 2.3 Fail closed on unresolved placeholders; never infer IDs from suffixes
- [x] 2.4 Add unit tests that placeholders resolve in memory and that unresolved placeholders block writes

## 3. JUnit emission

- [x] 3.1 After `qaAutomated`, write JUnit XML under `build/test-results/qaAutomated/` with one testcase per `AUTOMATED_SCENARIO_IDS` entry
- [x] 3.2 Map FAIL / unexpected BLOCKED / crash to JUnit failure or error; omit manual scenarios
- [x] 3.3 Make the Gradle `qaAutomated` task fail when JUnit reports failures
- [x] 3.4 Add unit tests for the JUnit writer

## 4. GitHub Actions workflow

- [x] 4.1 Add `.github/workflows/end-to-end-qa.yml` on `pull_request` types opened/synchronize/reopened/labeled
- [x] 4.2 Gate with same-repo, label `end-to-end-qa`, and author `justinfiore` or `jhorgenson`
- [x] 4.3 Map the eight secrets to env; set `QA_CONFIRM_LIVE_MUTATIONS=YES`; do not write `tokens.txt`
- [x] 4.4 Run automated suite only; skip nested `testAll` in A1 when `GITHUB_ACTIONS` is set
- [x] 4.5 Upload `build/test-results/` and publish suite totals plus failing names via the in-repo summary script
- [x] 4.6 Fail the job on JUnit failure; pin every `uses:` to a full SHA; add concurrency so two live campaigns do not overlap

## 5. Docs and verification

- [x] 5.1 Update `AGENTS.md`, `qa/SETUP.md`, and `qa/README.md` with the label, authors, secrets, and env-only rule
- [x] 5.2 Run `python3 -m unittest discover -s qa/tests -v`
- [x] 5.3 Run `./gradlew testAll`
- [x] 5.4 Confirm `./gradlew check --dry-run` still does not include `qaAutomated` / `qaManual`

## 6. HTTP 429 resume and cleanup pacing amendment

- [x] 6.1 Retry 429 for every QA HTTP method until success by default, with a finite test override and redacted errors/telemetry
- [x] 6.2 Honor case-insensitive Retry-After seconds/date and reset epochs, with capped linear fallback
- [x] 6.3 Apply validated `QA_CLEANUP_PACING_MS` pacing across both cleanup windows and disable it afterward
- [x] 6.4 Run full `qaAutomated` in the labeled workflow with step-scoped pacing and a 360-minute timeout
- [x] 6.5 Update focused QA tests, documentation, and this OpenSpec delta
- [x] 6.6 Run `python3 -m unittest discover -s qa/tests -v` and review the diff

## 7. Actions run 33313572332 failure-remediation amendment

- [x] 7.1 Scope B/C/D snapshots and assertions to exact campaign plus scenario, exclude deleted transactions by default, strengthen C1 identity/field checks, and add contamination regression tests
- [x] 7.2 Upgrade the production Groovy `YnabHttpClient` to safely retry 429 for every method with injectable limits/time/sleep, resume-header parsing, redacted errors, and complete offline tests
- [x] 7.3 Isolate independent live scenario execution and cleanup failures, preserve B1→B2 dependency semantics, always finalize metadata/cleanup, and test continuation through C3 and D4
- [x] 7.4 Correct JUnit pass/failure/error/skipped and aggregate semantics while preserving campaign exit gating, with mixed-result and crash-receipt tests
- [x] 7.5 Add an always-safe partial CI evidence packager, deterministic campaign pointer, sanitization/refusal/checksums/ZIP, workflow upload steps, and synthetic partial-campaign tests
- [x] 7.6 Update `qa/README.md`, design, and delta specs for the amended runtime, reporting, retry, and evidence behavior without changing `AGENTS.md`
- [x] 7.7 Run `python3 -m unittest discover -s qa/tests -v` and `./gradlew testAll`, then review changed files and task status
