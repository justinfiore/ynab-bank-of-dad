## Why

Allowance, parent-child sync, and disposable-plan QA all share the same YNAB limit: **200 requests per hour per access token**, after which the API returns HTTP 429. Both `YnabHttpClient` and `YnabQaClient` already retry 429s with header-driven or linear backoff, but they still use a single token per account and they do not log remaining/reset information at INFO. Live QA and production runs hit 429s often, so a long sleep on the first 429 wastes hours when another personal access token for the same YNAB account could continue immediately.

## What Changes

- Inspect every HTTP 429 **response body and headers** for remaining-request and reset/retry information. Use whatever is actually present to drive backoff. Log that information at **INFO** (never the token value, Authorization header, URL, or plan/transaction IDs).
- Treat each access-token environment variable as either a single token or a **comma-separated list** of tokens. Affected names stay the same: `YNAB_ACCESS_TOKEN` (allowance CLI) and each configured `tokenEnvVarName` (parent/child sync), plus QA `PARENT_ACCESS_TOKEN`, `JORSTEN_JR_ACCESS_TOKEN`, `BORSTEN_ACCESS_TOKEN`, and `THORSTEN_ACCESS_TOKEN` (env and `tokens.txt`).
- On 429, if another unused token remains for that YNAB account, **switch to the next token and retry the same call immediately** (no sleep).
- Only after **every** token for that account has returned 429 for the same logical call, enter the existing backoff sleep, then retry.
- Apply the same rotation + logging + backoff behavior in **production Groovy** (`YnabHttpClient` and token loading) **and** **QA Python** (`YnabQaClient` and `load_tokens`).
- Document CSV token syntax. Single-token env values remain valid.

Official YNAB docs (https://api.ynab.com/#rate-limiting) currently document a 429 JSON body with only `error.id` / `error.name` / `error.detail`, and changelog **v1.73.0** (2025-01-29) states that `X-Rate-Limit` is **no longer included** on 429. This change must not invent remaining-count fields. It must parse documented/common headers if they appear (`Retry-After`, reset-epoch variants, any remaining `X-Rate-Limit`), log presence/absence, and fall back to the existing capped linear backoff when no usable remaining/reset data exists.

## Capabilities

### New Capabilities

- `ynab-http-rate-limiting`: Per-token YNAB 429 handling for production and disposable QA: inspect 429 body/headers, INFO-log remaining/reset without leaking secrets, accept CSV access-token env values, rotate tokens before sleeping, and backoff only after every token for that account has 429'd the same call.

### Modified Capabilities

- None. Existing specs still require the same env-var **names** (`YNAB_ACCESS_TOKEN`, per-budget `tokenEnvVarName`) and still forbid logging raw tokens. CSV values and 429 rotation are additive HTTP-client behavior, not a change to allowance/sync domain requirements.

## Impact

- `src/main/groovy/ynabbankofdad/ynab/YnabHttpClient.groovy` — rebuild Authorization on token switch; inspect 429 body/headers; INFO log; rotate then backoff.
- `src/main/groovy/ynabbankofdad/allowance/RecordAllowance.groovy` — parse `YNAB_ACCESS_TOKEN` as CSV.
- `src/main/groovy/ynabbankofdad/sync/ParentChildBudgetSyncer.groovy` — `resolveRequiredToken` / `YnabHttpClient` construction for parent and each child `tokenEnvVarName`.
- `src/test/groovy/YnabHttpClientSpec.groovy` and related WireMock specs — rotation, CSV, INFO redaction, backoff-after-all-tokens.
- `qa/lib/ynab_qa_client.py`, `qa/lib/qa_config.py` — CSV tokens, rotation, INFO logging, whitespace-around-commas (today `YnabQaClient` rejects any whitespace in a token).
- `qa/tests/test_qa_helpers.py`, `qa/tests/test_qa_ci.py`, and other QA client tests.
- Docs: `README.md`, `QUICK_START.md`, `CONFIGURATION.md`, `qa/SETUP.md`, `qa/README.md`, `qa/config/tokens.txt.example`.
- No new env-var **names**. No YNAB YAML naming changes. No change to transaction semantics, `--dry-run`, or GitHub secret names (secret **values** may become CSV).
