## Why

The repo now has a working Gradle test suite on the supported Java 25 / Groovy 5 toolchain, but there is still no remote CI path to build the project or run those tests automatically on pushes and pull requests. Adding GitHub Actions closes that gap so regressions are caught without requiring manual local runs and so test reports remain available when a branch fails in review.

## What Changes

- Add a GitHub Actions workflow that checks out the repo, provisions the supported Java toolchain, and runs both `./gradlew test` and `./gradlew installDist` on pushes and pull requests.
- Require hash-pinned external workflow dependencies so Actions and any referenced container images are locked to immutable digests rather than floating tags.
- Avoid recently compromised or unnecessary third-party Actions, especially risky supply-chain helpers such as `tj-actions/changed-files`, in favor of minimal, well-understood workflow dependencies.
- Configure the workflow to preserve test outputs as downloadable artifacts so failing runs can be inspected remotely.
- Update repository documentation to explain the CI workflow, badge, supply-chain-hardening choices, what events trigger it, and where to find build/test artifacts.
- Add a README status badge in the first pass.

## Capabilities

### New Capabilities
- `github-actions-ci`: GitHub-hosted continuous integration for building the repo and running automated tests with preserved test artifacts.

### Modified Capabilities
- `automated-test-coverage`: Extend the test-verification contract so automated tests are exercised through a documented GitHub Actions workflow in addition to local Gradle runs.

## Impact

- GitHub workflow files under `.github/workflows/`
- `README.md` CI/build documentation and possibly a workflow badge
- OpenSpec change artifacts under `openspec/changes/add-github-actions-ci/`
- Existing automated-test-coverage spec deltas describing CI-backed verification expectations
