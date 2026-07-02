## 1. Add hardened GitHub Actions CI workflow

- [x] 1.1 Create a GitHub Actions workflow under `.github/workflows/` that checks out the repo, provisions the supported Java baseline, and runs both `./gradlew testAll` and `./gradlew installDist` on push and pull request events.
- [x] 1.2 Ensure every external Action reference is pinned to a full commit SHA, any referenced container image is pinned by digest, and the workflow avoids unnecessary or recently compromised third-party helper Actions.
- [x] 1.3 Configure the workflow to upload `build/test-results/`, `build/reports/tests/`, and the installDist output as CI artifacts for remote inspection.

## 2. Align docs and repository expectations

- [x] 2.1 Update `README.md` to document the CI workflow, trigger conditions, artifact locations, and the supply-chain-hardening choices made in the workflow.
- [x] 2.2 Add a README workflow badge in the first implementation pass and keep its path aligned with the chosen stable workflow name.

## 3. Verify and prepare for review

- [x] 3.1 Run the applicable local verification commands for any workflow-supporting repo changes, including `./gradlew testAll` and `./gradlew installDist` if workflow or build behavior changes require local confirmation.
- [x] 3.2 Validate the OpenSpec change with `openspec validate add-github-actions-ci` and confirm the branch is ready for the implementation step.
