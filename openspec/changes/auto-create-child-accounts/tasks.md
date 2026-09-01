## 1. Config model

- [x] 1.1 Add `autoCreateAccounts`, `createdAccountOnBudget`, and `accountCreationNameStripRegex` to `ChildBudgetSyncTarget` with the defaults in design.md.
- [x] 1.2 Parse those keys in `RuntimeConfig.requireChildBudgets` without changing `accountMappings` requirements.
- [x] 1.3 Validate booleans, compile `accountCreationNameStripRegex` as a Java Pattern, and fail fast with child-path errors.
- [x] 1.4 Add `RuntimeConfigSpec` cases for omit/default, explicit true/false, invalid types, valid strip regex, invalid strip regex, and empty strip regex.

## 2. YNAB create-account wrapper

- [x] 2.1 Re-read the live YNAB OpenAPI `SaveAccount` / `PostAccountWrapper` schema before writing the request body. Do not guess fields.
- [x] 2.2 Add a repository method that POSTs `/v1/plans/{planId}/accounts` through `YnabHttpClient.postJsonWithMetadata` with `type: savings` and `balance: 0`.
- [x] 2.3 Require HTTP 201 and a non-empty `data.account.id`; never log access tokens.
- [x] 2.4 Send `on_budget` only if the live schema accepts it; if `createdAccountOnBudget` is false and the schema cannot express tracking, fail closed without changing `type`.
- [x] 2.5 Add a list-or-find helper so routing can look up accounts by exact name without throw-on-miss.
- [x] 2.6 Add WireMock/unit coverage for create success, missing `data.account.id`, and lookup-by-name.

## 3. Sync routing

- [x] 3.1 In `resolveChildRouting`, use mapped `childAccountName` when it exists.
- [x] 3.2 When it is missing and `autoCreateAccounts` is false, keep today's missing-account routing failure.
- [x] 3.3 When it is missing and auto-create is on, derive the name from the parent category, apply `accountCreationNameStripRegex` as replace-with-empty, and trim.
- [x] 3.4 Reuse an existing account with the derived name; create at most once per derived name per child per cycle.
- [x] 3.5 On live runs, POST create then cache the id and continue planning in the same cycle.
- [x] 3.6 On `--dry-run`, log the planned Savings create and do not POST; do not plan financial mutations for those categories.
- [x] 3.7 Do not create accounts for unmapped parent categories.
- [x] 3.8 Fail routing without POSTing when the derived name is empty.

## 4. Tests

- [x] 4.1 Add WireMock coverage that a live auto-create run POSTs `type: savings` and `balance: 0`.
- [x] 4.2 Add coverage that `--dry-run` and `autoCreateAccounts: false` issue no create-account POST.
- [x] 4.3 Add coverage that strip regex ` Bank$` turns `Child One Spend Bank` into `Child One Spend`.
- [x] 4.4 Add coverage that an existing derived-name account is reused with no second POST.
- [x] 4.5 Add coverage that unmapped categories never trigger create.
- [x] 4.6 Add an end-to-end WireMock scenario covering missing account → create savings account → mirror the parent transaction in the same cycle.
- [x] 4.7 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew testAll`.
- [x] 4.8 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew installDist`.

## 5. Docs

- [x] 5.1 Document the three new child-budget keys in `config.yaml.example` as optional, default-off.
- [x] 5.2 Update `CONFIGURATION.md` (and any other sync docs that describe child account lookup) with find-or-create, name stripping, dry-run, and the tracking/on-budget limitation.
