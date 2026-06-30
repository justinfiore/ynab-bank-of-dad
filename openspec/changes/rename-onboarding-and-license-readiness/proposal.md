## Why

The repo now has a modernized build, tests, and GitHub Actions CI, but its public-facing onboarding still reads like an internal family tool and the project lacks an explicit open-source licensing story. Before broadening reuse or making the repository public, the project needs a clearer external-facing name, newcomer-oriented setup documentation, and a committed license so outside users can understand what the tool is, how to try it safely, and what terms govern reuse.

## What Changes

- Rename the project presentation from `YNAB Bank of Dad` to `YNABBankOfDad` in docs and user-facing references where that tighter open-source project name improves consistency, while preserving existing runtime behavior and YNAB data assumptions.
- Rewrite `README.md` for an external open-source audience so it explains purpose, prerequisites, setup, safety constraints, supported workflow, and current limitations without assuming family-specific background context.
- Add `QUICK_START.md` with concise first-run instructions that help a new user validate the tool safely via `--dry-run` before any real YNAB transaction posting.
- Add a repository `LICENSE` file using the MIT License and document that licensing choice in onboarding docs.
- Update backlog/docs context so repository-hosting readiness reflects the current state: the repo is already on GitHub, but public-hosting readiness still depends on explicit licensing and onboarding improvements rather than a platform move.

## Capabilities

### New Capabilities
- `open-source-onboarding`: External-facing project naming and newcomer setup documentation for safe first use of the CLI.
- `repository-licensing`: Explicit repository license presence and documentation for open-source/public-hosting readiness.

### Modified Capabilities
- `modernized-build-toolchain`: Update build/setup documentation requirements so the supported Java 25 / Gradle workflow is presented in external-facing onboarding materials, not only internal maintenance notes.

## Impact

- `README.md` project naming, positioning, setup, safety, and contribution/onboarding guidance
- New `QUICK_START.md` and `LICENSE` files
- Potential minor doc/reference updates in helper docs such as `TODO.md` or repo guidance where project naming or public-hosting readiness is described
- OpenSpec change artifacts under `openspec/changes/rename-onboarding-and-license-readiness/`
- Delta specs describing onboarding and licensing requirements
