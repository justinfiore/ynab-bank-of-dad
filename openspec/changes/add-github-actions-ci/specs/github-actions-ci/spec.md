## ADDED Requirements

### Requirement: The repository SHALL provide a GitHub Actions workflow for automated Gradle verification and packaging
The repository SHALL include at least one GitHub Actions workflow under `.github/workflows/` that runs on push and pull request events, checks out the repository, provisions the documented supported Java baseline for this repo, executes `./gradlew testAll`, and executes `./gradlew installDist` without requiring a live `YNAB_ACCESS_TOKEN`.

#### Scenario: Pushes trigger automated Gradle verification and packaging
- **WHEN** a contributor pushes commits to a branch in the GitHub-hosted repository
- **THEN** GitHub Actions SHALL run the repository's defined CI workflow
- **AND** that workflow SHALL execute both `./gradlew testAll` and `./gradlew installDist` on a GitHub-hosted runner using the repo's documented supported Java baseline

#### Scenario: Pull requests trigger the same verification path
- **WHEN** a pull request is opened, synchronized, or reopened against the default branch
- **THEN** the GitHub Actions workflow SHALL run the same automated Gradle verification and packaging path used for pushes
- **AND** it SHALL do so without requiring a configured `YNAB_ACCESS_TOKEN` secret

### Requirement: The workflow SHALL preserve build and test artifacts for remote inspection
The GitHub Actions workflow SHALL retain the repository's generated test and packaging artifacts so maintainers can inspect failures and results remotely, including the Gradle-produced JUnit XML under `build/test-results/`, HTML reports under `build/reports/tests/`, and the distribution outputs produced by `installDist`.

#### Scenario: Build and test artifacts are available after CI completes
- **WHEN** a CI run completes after executing the Gradle verification and packaging workflow
- **THEN** the run SHALL publish the generated test-result, test-report, and installDist output directories as GitHub Actions artifacts
- **AND** maintainers SHALL be able to download those artifacts without reproducing the run locally

### Requirement: The workflow SHALL surface test outcomes directly in pull-request-visible GitHub UI
The GitHub Actions workflow SHALL publish a human-readable test summary into GitHub's run summary or check output so reviewers can see pass/fail counts directly from the pull request checks experience, and when tests fail it SHALL identify the failing test cases without requiring artifact download.

#### Scenario: Passing runs show an inline summary
- **WHEN** the CI workflow finishes with passing unit and integration tests
- **THEN** reviewers SHALL be able to see a direct summary of executed test suites and counts from the GitHub checks/run UI linked from the pull request
- **AND** they SHALL not need to download the JUnit artifact just to confirm what ran

#### Scenario: Failing runs list failing test cases inline
- **WHEN** one or more tests fail in the CI workflow
- **THEN** the GitHub checks/run UI SHALL identify the failing test cases and their suite context directly in the visible summary
- **AND** reviewers SHALL not need to download artifacts first to learn which tests failed

### Requirement: External workflow dependencies SHALL be pinned to immutable identities
The GitHub Actions workflow SHALL pin every external GitHub Action reference to a full commit SHA and SHALL pin any referenced container image to an immutable digest, rather than using floating tags, branch names, or unpinned image references.

#### Scenario: Workflow dependencies are immutable
- **WHEN** a maintainer inspects the CI workflow definition
- **THEN** every `uses:` reference to an external GitHub Action SHALL include a full commit SHA
- **AND** any referenced container image SHALL be identified by digest instead of a mutable tag alone

#### Scenario: Recently compromised convenience actions are avoided
- **WHEN** the workflow is implemented for this repository's initial CI slice
- **THEN** it SHALL avoid unnecessary third-party helper actions that increase supply-chain risk
- **AND** it SHALL not rely on recently compromised convenience actions such as `tj-actions/changed-files` for core workflow behavior
