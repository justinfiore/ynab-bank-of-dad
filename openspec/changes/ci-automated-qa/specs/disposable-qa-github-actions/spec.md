## ADDED Requirements

### Requirement: Opt-in end-to-end QA SHALL run only on authorized same-repo pull requests
The repository SHALL provide a GitHub Actions workflow that runs the rate-budgeted disposable-plan smoke suite (`qaAutomatedSmoke`: A2, A3, B1, B2) on pull requests when, and only when, all of the following are true: the event is a pull request against this repository; the pull request head repository equals `github.repository` (not a fork); the pull request has the label `end-to-end-qa`; and the pull request author login is `justinfiore` or `jhorgenson`. The broader `qaAutomated` suite SHALL remain explicit and SHALL NOT be silently reduced.

#### Scenario: Labeled maintainer PR runs live automated QA
- **WHEN** a pull request in `justinfiore/ynab-bank-of-dad` authored by `justinfiore` or `jhorgenson` has the label `end-to-end-qa`
- **THEN** GitHub Actions SHALL start the end-to-end QA job
- **AND** that job SHALL execute the reviewed rate-budgeted smoke scenario set against the four disposable QA plans

#### Scenario: Missing label skips live QA
- **WHEN** a pull request does not have the label `end-to-end-qa`
- **THEN** the end-to-end QA job SHALL NOT run live YNAB mutations

#### Scenario: Unauthorized author is skipped
- **WHEN** a pull request author is not `justinfiore` or `jhorgenson`
- **THEN** the end-to-end QA job SHALL NOT run even if the label is present

#### Scenario: Fork PRs never receive disposable-plan secrets
- **WHEN** a pull request head repository is not the main repository
- **THEN** the end-to-end QA job SHALL NOT run
- **AND** disposable-plan token secrets SHALL NOT be exposed to that workflow run

### Requirement: Live QA SHALL use environment variables and SHALL NOT write tokens to disk
The end-to-end QA job and `qa/run_qa_suite.py` SHALL obtain YNAB tokens only from environment variables (`PARENT_ACCESS_TOKEN`, `JORSTEN_JR_ACCESS_TOKEN`, `BORSTEN_ACCESS_TOKEN`, `THORSTEN_ACCESS_TOKEN`). They SHALL NOT require `tokens.txt`, SHALL NOT create `tokens.txt`, and SHALL NOT write token values to any file on the runner.

#### Scenario: Env-only tokens are sufficient
- **WHEN** the four token environment variables are set and `tokens.txt` is absent
- **THEN** `qaAutomated` SHALL proceed past token loading
- **AND** no token file SHALL be created

#### Scenario: Missing token env fails closed
- **WHEN** any required token environment variable is unset or empty and is not supplied by an existing local `tokens.txt` for a laptop run
- **THEN** the suite SHALL exit blocked before any YNAB write
- **AND** it SHALL NOT write a token file

### Requirement: QA plan IDs SHALL resolve from env without embedding tokens in yaml on disk
QA config loading SHALL accept `qa/config/qa-sync.yaml` (or the checked-in example) whose `fullId` values are complete UUIDs or environment placeholders (`${QA_PARENT_PLAN_ID}`, `${QA_JORSTEN_JR_PLAN_ID}`, `${QA_BORSTEN_PLAN_ID}`, `${QA_THORSTEN_PLAN_ID}`). Expansion SHALL occur in memory. CI SHALL NOT write a rendered config that contains token values. Uploaded artifacts SHALL NOT contain raw tokens, Authorization header values, or unredacted full plan UUIDs.

#### Scenario: Placeholder yaml plus env IDs allow discovery
- **WHEN** the example or runner yaml uses env placeholders for the four plan `fullId` values and those env vars are set
- **THEN** discovery SHALL validate exact display name plus resolved full UUID
- **AND** no token value SHALL be written into that yaml file

#### Scenario: Unresolved placeholder fails closed
- **WHEN** a `fullId` placeholder has no corresponding environment value
- **THEN** QA SHALL block before writes
- **AND** it SHALL NOT invent or suffix-match a plan ID

### Requirement: Automated CI SHALL run a reviewed, rate-budgeted smoke subset
The Actions job SHALL run only the checked-in `qaAutomatedSmoke` selection (A2, A3, B1, B2). It SHALL NOT run `qaAutomated`, `qaManual`, or scenarios `A8-money-movement`, `B5-live-movement`, or `C8-split-component-removed`. The broader UI-free scenario matrix remains available through explicit `qaAutomated` execution.

#### Scenario: Manual scenarios are not executed in Actions
- **WHEN** the end-to-end QA job runs
- **THEN** it SHALL invoke the automated suite only
- **AND** it SHALL NOT create Move Money or C8 UI fixtures as a required CI step

### Requirement: Each automated scenario SHALL appear as a JUnit result that can fail the PR check
The job SHALL write JUnit XML under `build/test-results/` with one testcase per automated scenario. A scenario status of `FAIL`, unexpected `BLOCKED`, or an executor crash SHALL be a JUnit failure or error. A non-zero suite or JUnit failure SHALL fail the GitHub Actions job. The PR check UI SHALL show suite totals and failing test names without requiring artifact download.

#### Scenario: Failing scenario fails the build
- **WHEN** any automated scenario records `FAIL` or unexpected `BLOCKED`
- **THEN** the corresponding JUnit testcase SHALL fail
- **AND** the workflow job SHALL conclude unsuccessfully

#### Scenario: Passing campaign is visible on the PR
- **WHEN** every automated scenario records `PASS`
- **THEN** the published JUnit results and check summary SHALL list those scenario names and a passing total
- **AND** the job SHALL succeed

### Requirement: Default Gradle verification SHALL remain token-free and SHALL NOT run live QA
`test`, `testAll`, `check`, `build`, `installDist`, and the existing `build-test` job SHALL NOT depend on `qaAutomated` or the new end-to-end workflow.

#### Scenario: Ordinary PR without the label
- **WHEN** a pull request is opened without `end-to-end-qa`
- **THEN** `build-test` SHALL still run `./gradlew testAll` and `./gradlew installDist`
- **AND** no disposable-plan token SHALL be required
