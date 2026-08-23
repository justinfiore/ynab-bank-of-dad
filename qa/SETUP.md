# Disposable YNAB QA setup

These instructions are for the four **test-only** plans. Never point this harness at a real family budget.

## 1. Create the four disposable plans

In YNAB, create empty plans with these **exact** display names:

1. Parent: `Jorsten's Plan`
2. Child: `Jorsten Jr's Plan`
3. Child: `Borsten's Plan`
4. Child: `Thorsten's Plan`

## 2. Child accounts (UI only)

The public API cannot create accounts. In each child plan create two unlinked cash accounts named exactly:

- `Silver`
- `Bronze`

## 3. Parent categories

Required names, in a dedicated group such as `BOD Reconciliation QA`:

- `QA Jorsten Jr Silver`
- `QA Jorsten Jr Bronze`
- `QA Borsten Silver`
- `QA Borsten Bronze`
- `QA Thorsten Silver`
- `QA Thorsten Bronze`
- `QA Unmapped`
- `QA Transfer Clearing`

You can create missing categories with `qa/provision_categories.py` after the local config exists. Do not create them in a family plan.

## 4. Tokens

1. Copy `qa/config/tokens.txt.example` to `tokens.txt` at the repo root.
2. `chmod 0600 tokens.txt`
3. Put one developer token per QA plan. Do not commit the file. It is gitignored.
4. Copy `qa/config/qa-sync.yaml.example` to `qa/config/qa-sync.yaml`.
5. Replace every `fullId` with the **complete** immutable plan UUID from YNAB. Suffixes are rejected.

## 5. Live confirmation

Live suites refuse to run unless:

```bash
QA_CONFIRM_LIVE_MUTATIONS=YES
```

or Gradle `-PqaConfirmLive=YES`.
