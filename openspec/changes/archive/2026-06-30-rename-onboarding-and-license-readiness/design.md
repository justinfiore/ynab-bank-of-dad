## Context

This change is documentation- and repository-metadata-heavy rather than a behavioral code change. The current README contains useful technical details, but it is still shaped by the history of the project as a private family utility: it mixes internal modernization notes with external onboarding, uses the spaced name `YNAB Bank of Dad`, and highlights family-specific current-state details before explaining how a newcomer can safely evaluate the tool. The backlog also still phrases public-hosting readiness as moving the repo to GitHub, but that move is already complete; the remaining readiness gap is explicit licensing plus clearer onboarding for outside users.

The repository already has an established technical baseline that should be preserved in the new docs: Java 25, Gradle 9.6.1, Groovy 5, `YNAB_ACCESS_TOKEN`, exact YNAB naming assumptions, and a `--dry-run` first-run safety pattern. Because the tool can post real transactions to YNAB, onboarding content must stay safety-forward and must not imply that the project is turnkey for arbitrary budgets without matching category/account conventions.

## Goals / Non-Goals

**Goals:**
- Present the project consistently as `YNABBankOfDad` in open-source-facing docs while preserving runtime behavior and repository history.
- Produce a README aimed at external readers evaluating the repo for the first time.
- Add a concise quick-start document that gets a user from clone to safe dry-run with minimal ambiguity.
- Add an explicit open-source license file and document that licensing in the onboarding docs.
- Correct public-hosting-readiness framing so it reflects that GitHub hosting is already done and the remaining work is documentation/license readiness.

**Non-Goals:**
- Renaming the GitHub repository slug, code/package identifiers, or project-file names beyond docs as part of this proposal.
- Adding contribution-process guidance in this change.
- Externalizing hard-coded family/account configuration.
- Changing runtime logic, YNAB API behavior, CLI flags, or transaction semantics.
- Making the repository public as part of this change.

## Decisions

### 1. Treat the rename as a user-facing documentation/identity update, not a behavioral rename
The proposal covers renaming "where appropriate". To keep scope practical, the design treats that as doc and presentation updates only: README title, quick-start docs, and other onboarding references should prefer `YNABBankOfDad`. Code package/class names, repository slug, project-file names beyond docs, branch history, and runtime identifiers stay unchanged.

**Alternative considered:** perform a full repo/code rename immediately. Rejected because it would broaden scope into code, build, and potentially repository URL changes that are not required to improve onboarding.

### 2. Split onboarding into README + QUICK_START with distinct jobs
The README should explain the project, constraints, capabilities, and overall workflow. `QUICK_START.md` should optimize for the first successful evaluation path: prerequisites, token setup, dry-run invocation, and what to inspect before posting real transactions.

**Alternative considered:** keep all onboarding in README only. Rejected because the README is already dense, and a shorter quick-start improves first-run usability without losing deeper context.

### 3. Add an MIT license file and surface the choice in docs
Repository licensing readiness requires an actual root-level `LICENSE` file plus a brief explanation in the docs. This change will use the MIT License, committed explicitly and reflected in onboarding.

**Alternative considered:** defer license choice until after other docs work. Rejected because the backlog item explicitly calls for licensing readiness, and outside users need license clarity alongside onboarding.

### 4. Preserve and foreground operational safety constraints
New docs must clearly preserve the current constraints: `YNAB_ACCESS_TOKEN` is required, exact YNAB names matter, and `--dry-run` should be the first-run path. The onboarding rewrite should simplify presentation without overselling generality or hiding risk.

**Alternative considered:** streamline docs by downplaying current constraints. Rejected because this tool can create real financial transactions and depends on exact naming conventions.

## Risks / Trade-offs

- **[Over-scoping the rename]** → Limit this change to external-facing docs and obviously user-facing references unless a small adjacent update is clearly necessary.
- **[Choosing a license the owner does not want]** → Keep the implementation explicit about the selected license and ensure it is easy to review before any public-hosting change.
- **[Docs become friendlier but less accurate]** → Anchor all onboarding text to the existing verified commands, runtime prerequisites, and known YNAB assumptions already documented in the repo.
- **[Users infer the tool is generic/out-of-the-box]** → Include clear limitations and naming-assumption sections in both README and quick-start guidance.

## Migration Plan

1. Draft the new onboarding and licensing requirements as delta specs.
2. Update README and add QUICK_START in line with those requirements.
3. Add the MIT `LICENSE` file and reference it from README.
4. Update TODO/backlog wording if needed so public-hosting readiness no longer implies the repo still needs to move to GitHub.
5. Validate the OpenSpec change and verify doc-oriented repo commands as needed.

## Open Questions

- None. This change now assumes the MIT License, defers contribution guidance to a later change, and limits the `YNABBankOfDad` alignment to documentation/user-facing onboarding references only.
