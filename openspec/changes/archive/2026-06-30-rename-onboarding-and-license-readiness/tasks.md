## 1. Define onboarding and licensing scope

- [x] 1.1 Keep the `YNABBankOfDad` alignment limited to documentation and user-facing onboarding references in this change.
- [x] 1.2 Use the MIT License and capture that choice in the change artifacts and implementation files.
- [x] 1.3 Update any backlog/readiness wording that still implies GitHub hosting itself is unfinished.

## 2. Refresh open-source-facing onboarding docs

- [x] 2.1 Rewrite `README.md` for an external open-source audience while preserving accurate prerequisites, naming assumptions, safety notes, and verified commands.
- [x] 2.2 Add `QUICK_START.md` with concise clone-to-dry-run instructions, including token setup, Java/Gradle prerequisites, and first-run checks.
- [x] 2.3 Align other user-facing documentation references touched by this change to the chosen `YNABBankOfDad` naming scope, without renaming code or non-doc project files.

## 3. Add licensing artifacts and references

- [x] 3.1 Add a root-level `LICENSE` file containing the MIT License text.
- [x] 3.2 Update onboarding docs so the active license and the path to `LICENSE` are clear to readers.

## 4. Validate and prepare for implementation

- [x] 4.1 Run `openspec validate rename-onboarding-and-license-readiness` and fix any artifact issues.
- [x] 4.2 Verify the final doc plan against current repo reality, including GitHub hosting state and the supported Java 25 / Gradle workflow reflected in onboarding docs.
