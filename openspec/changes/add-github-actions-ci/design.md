## Context

The repository now has a verified local Gradle test workflow on Java 25, but contributors and reviewers still depend on developers running those checks manually. The next TODO item asks for GitHub Actions that build, run, and report on tests, which makes this a cross-cutting change touching repository automation, artifact retention, and contributor documentation rather than a code-only tweak.

This repo is a CLI that can create real YNAB transactions, but the CI workflow itself should stay entirely on the build/test path. The existing automated tests already avoid live YNAB calls and do not require `YNAB_ACCESS_TOKEN`, so CI can safely execute `./gradlew test` in GitHub-hosted runners without exposing budget credentials.

## Goals / Non-Goals

**Goals:**
- Add a GitHub Actions workflow that runs on pushes and pull requests.
- Use the repository's supported Java toolchain so CI matches the documented local workflow as closely as practical.
- Preserve build/test outputs, especially JUnit XML and HTML test reports, as downloadable artifacts for failed and successful runs.
- Document the CI behavior in `README.md`, including what it runs and where to find artifacts.
- Keep the change small and incremental, focused on automated verification rather than broader release automation.

**Non-Goals:**
- Publishing distributions, releases, or packages.
- Running live YNAB dry-runs or any workflow that requires `YNAB_ACCESS_TOKEN`.
- Adding deployment automation, code coverage SaaS integrations, or branch protection policy changes.
- Reworking the existing Gradle build beyond what is minimally necessary for reliable CI execution.

## Decisions

### Decision: Use a single GitHub Actions workflow focused on pinned Gradle verification and packaging
A single workflow under `.github/workflows/` is the smallest slice that satisfies the TODO item. It will check out the repo, set up Java, run `./gradlew test`, and run `./gradlew installDist`.

**Why this over multiple workflows now?**
- The TODO item asks for build, run, and report on tests; adding `installDist` covers the packaging/build slice without expanding to a full matrix.
- A single workflow keeps maintenance and onboarding simple for a small brownfield repo.
- If the repo later needs release automation or multiple Java versions, those can be added after the base CI path is working.

### Decision: Use only hash-pinned, minimal workflow dependencies
Every external GitHub Action reference and any referenced container image in the workflow should be pinned to an immutable commit SHA or image digest. The workflow should rely on the smallest practical set of dependencies and avoid recently compromised or unnecessary third-party helper Actions.

**Why this over convenience Actions or tag-based references?**
- Floating tags and branch references increase exposure to supply-chain compromise.
- The recent `tj-actions/changed-files` compromise is a concrete example of why convenience helpers should not be added casually.
- For this repo's CI slice, first-party GitHub-maintained actions plus shell steps are sufficient, which reduces third-party trust surface.

### Decision: Target the documented supported Java baseline in CI
The workflow should use the repo's supported JDK version so remote CI validates the same baseline documented for local development.

**Why this over a Java-version matrix?**
- The repo documents a single supported Java baseline today.
- A matrix would add cost and noise before there is a stated compatibility requirement across multiple JDKs.
- Matching the documented baseline makes failures easier to reason about.

### Decision: Retain build and test artifacts from every run
The workflow should upload `build/test-results/`, `build/reports/tests/`, and the distribution outputs produced by `./gradlew installDist` so reviewers can inspect test failures and confirm packaging results even when they do not have the branch checked out locally.

**Why this over console logs only?**
- The TODO item explicitly calls for preserving or publishing results/artifacts.
- JUnit XML, HTML reports, and installDist outputs already exist locally, so artifact retention reuses the repo's current verification outputs.
- Richer artifacts reduce friction when debugging CI-only failures or packaging regressions.

### Decision: Keep secrets and transaction-capable runtime paths out of CI
The workflow should only run tasks that do not require `YNAB_ACCESS_TOKEN` or interact with the live YNAB API.

**Why this over adding dry-run app execution now?**
- Even dry-run application execution can drift toward environment-specific setup and raises unnecessary risk in a repo that can post real transactions outside dry-run mode.
- The current automated test suite already covers the intended safe verification slice.

### Decision: Document CI in README and include a status badge in the first pass
The README should describe how CI runs, why the workflow is SHA-pinned, which external dependencies are intentionally avoided, where artifacts live, and it should include a status badge from the first implementation pass.

**Why this over badge-only documentation?**
- A badge shows status but does not explain what is being verified or how to inspect failures.
- The TODO item also asks for documentation of workflow/repository settings.
- Recording the supply-chain-hardening decisions in the README makes the security posture reviewable rather than implicit.

## Risks / Trade-offs

- **[GitHub runner JDK availability/version drift]** → Use the closest supported setup action configuration for the repo's documented Java version; if exact patch parity is not possible, document the runner-installed baseline explicitly.
- **[Workflow dependency compromise or tag retargeting]** → Pin every external action to a full commit SHA, pin any container images by digest, and avoid unnecessary third-party actions entirely.
- **[Artifact uploads increase CI runtime and storage use]** → Limit uploads to the existing Gradle test result/report directories and the installDist output rather than archiving the whole build tree.
- **[Workflow naming/path changes can break a README badge]** → Choose a stable workflow name up front and update the badge path in the same change if it ever changes.
- **[CI green status could be mistaken for runtime safety]** → Document that CI validates build/test/package behavior only and does not run transaction-posting flows against YNAB.

## Migration Plan

1. Add the GitHub Actions workflow file under `.github/workflows/`, using only minimal external dependencies pinned to immutable SHAs or digests.
2. Run the repository verification commands locally if workflow-supporting build changes are needed.
3. Update `README.md` with CI workflow details, status badge, artifact expectations, and supply-chain-hardening notes.
4. Validate the OpenSpec change and open the branch for review so the workflow can execute in GitHub.
5. If the workflow fails remotely, iterate on workflow/build details without changing the production runtime contract or relaxing the pinning/security posture.

Rollback is low risk: remove or disable the workflow file and revert the accompanying documentation if the CI path proves unreliable.

## Open Questions

- None currently. The first implementation pass should include a README badge, run both `./gradlew test` and `./gradlew installDist`, and keep all workflow dependencies hash-pinned with a minimized trust surface.
