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

## 4. Tokens and plan IDs

Local laptop:

1. Export the four token env vars, or copy `qa/config/tokens.txt.example` to gitignored `tokens.txt` (`chmod 0600`). Env vars win. Never commit tokens. The suite will not write `tokens.txt`.
2. Copy `qa/config/qa-sync.yaml.example` to `qa/config/qa-sync.yaml`.
3. Either put complete plan UUIDs in `fullId`, or keep `${QA_*_PLAN_ID}` and export those env vars. Suffixes are rejected.

GitHub Actions (opt-in):

- Repository secrets: `PARENT_ACCESS_TOKEN`, `JORSTEN_JR_ACCESS_TOKEN`, `BORSTEN_ACCESS_TOKEN`, `THORSTEN_ACCESS_TOKEN`, `QA_PARENT_PLAN_ID`, `QA_JORSTEN_JR_PLAN_ID`, `QA_BORSTEN_PLAN_ID`, `QA_THORSTEN_PLAN_ID`.
- Label a PR `end-to-end-qa` only if you are `justinfiore` or `jhorgenson`. Forks never run.
- The workflow copies the example yaml (placeholders only) and expands IDs in memory. It does not write tokens to disk.

## 5. Live confirmation

Live suites refuse to run unless:

```bash
QA_CONFIRM_LIVE_MUTATIONS=YES
```

or Gradle `-PqaConfirmLive=YES`.
