## 1. Update startup logging

- [x] 1.1 Edit `src/main/groovy/RecordAllowance.groovy` to remove the raw `YNAB_ACCESS_TOKEN` value from startup logging.
- [x] 1.2 Preserve existing environment-variable validation and YNAB bearer-token configuration behavior.

## 2. Verify the change

- [x] 2.1 Run `./gradlew tasks --all` to confirm the project still builds under the verified Java 8 toolchain.
- [x] 2.2 Run `./gradlew test` to confirm no existing verification flow regressed.

## 3. Finalize change artifacts

- [x] 3.1 Review the implementation against `openspec/changes/remove-access-token-logging/specs/safe-startup-logging/spec.md`.
- [x] 3.2 Update any nearby documentation only if implementation changes user-visible startup behavior beyond removing the secret from logs.
