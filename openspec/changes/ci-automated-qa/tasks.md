## 1. Token loading

- [ ] 1.1 Change `qa/run_qa_suite.py` `load_tokens()` to accept the four token env vars without requiring `tokens.txt`
- [ ] 1.2 Keep optional local `tokens.txt` fill-in for unset env vars only; never write or print token values
- [ ] 1.3 Add unit tests for env-only success, missing-env block, and no file creation

## 2. qa-sync.yaml env IDs

- [ ] 2.1 Add in-memory `${ENV}` / `$ENV` expansion for budget `fullId` values in the QA config loader used by discovery and `run_live_campaign.py`
- [ ] 2.2 Update `qa/config/qa-sync.yaml.example` to document the four `QA_*_PLAN_ID` placeholders
- [ ] 2.3 Fail closed on unresolved placeholders; never infer IDs from suffixes
- [ ] 2.4 Add unit tests that placeholders resolve in memory and that unresolved placeholders block writes

## 3. JUnit emission

- [ ] 3.1 After `qaAutomated`, write JUnit XML under `build/test-results/qaAutomated/` with one testcase per `AUTOMATED_SCENARIO_IDS` entry
- [ ] 3.2 Map FAIL / unexpected BLOCKED / crash to JUnit failure or error; omit manual scenarios
- [ ] 3.3 Make the Gradle `qaAutomated` task fail when JUnit reports failures
- [ ] 3.4 Add unit tests for the JUnit writer

## 4. GitHub Actions workflow

- [ ] 4.1 Add `.github/workflows/end-to-end-qa.yml` on `pull_request` types opened/synchronize/reopened/labeled
- [ ] 4.2 Gate with same-repo, label `end-to-end-qa`, and author `justinfiore` or `jhorgenson`
- [ ] 4.3 Map the eight secrets to env; set `QA_CONFIRM_LIVE_MUTATIONS=YES`; do not write `tokens.txt`
- [ ] 4.4 Run automated suite only; skip nested `testAll` in A1 when `GITHUB_ACTIONS` is set
- [ ] 4.5 Upload `build/test-results/` and publish suite totals plus failing names via the in-repo summary script
- [ ] 4.6 Fail the job on JUnit failure; pin every `uses:` to a full SHA; add concurrency so two live campaigns do not overlap

## 5. Docs and verification

- [ ] 5.1 Update `AGENTS.md`, `qa/SETUP.md`, and `qa/README.md` with the label, authors, secrets, and env-only rule
- [ ] 5.2 Run `python3 -m unittest discover -s qa/tests -v`
- [ ] 5.3 Run `./gradlew testAll`
- [ ] 5.4 Confirm `./gradlew check --dry-run` still does not include `qaAutomated` / `qaManual`
