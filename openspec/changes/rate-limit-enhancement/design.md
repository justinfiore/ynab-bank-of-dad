## Context

YNAB documents a **per-access-token** limit of 200 requests per hour in a rolling window. Exceeding it returns HTTP 429 with JSON:

```json
{ "error": { "id": "429", "name": "too_many_requests", "detail": "Too many requests" } }
```

Changelog **v1.73.0** (2025-01-29) states that a `X-Rate-Limit` header is **no longer included** on 429. The published Rate Limiting section does not document remaining-count fields, `Retry-After`, or reset-epoch headers.

Today both clients already retry 429 until success by default:

- Groovy `YnabHttpClient` (`src/main/groovy/ynabbankofdad/ynab/YnabHttpClient.groovy`) builds one `HttpRequest` with a single `Authorization: Bearer` token, sleeps using case-insensitive `Retry-After` or `RateLimit-Reset` / `X-RateLimit-Reset` / `X-Rate-Limit-Reset`, else 5s linear backoff capped at one hour, and logs at **WARN** `YNAB {} {} was rate limited; retrying after {}`.
- Python `YnabQaClient` (`qa/lib/ynab_qa_client.py`) does the same header/backoff logic. It currently **rejects any whitespace** in the token string.
- Allowance loads `YNAB_ACCESS_TOKEN` as one string (`RecordAllowance`). Sync loads one string per `tokenEnvVarName` (`ParentChildBudgetSyncer.resolveRequiredToken`). QA loads `PARENT_ACCESS_TOKEN`, `JORSTEN_JR_ACCESS_TOKEN`, `BORSTEN_ACCESS_TOKEN`, `THORSTEN_ACCESS_TOKEN` from env or `tokens.txt`.

Disposable QA and production both spend a lot of wall time sleeping on the first 429 even when the operator has additional personal access tokens for that same YNAB account.

## Goals / Non-Goals

**Goals:**

- Parse 429 **headers and body** for remaining/reset/retry metadata when present; use it for backoff; log it at **INFO** without leaking secrets.
- Allow each existing token env var (and QA `tokens.txt` values) to hold a CSV of tokens.
- On 429, rotate to the next unused token for that account and retry immediately.
- Sleep only after every token for that account has 429'd the same logical call.
- Keep this behavior identical in production Groovy and QA Python.
- Preserve Java 25 / Gradle 9 / Groovy 5, `--dry-run`, transaction semantics, env-var **names**, and “never log raw tokens”.

**Non-Goals:**

- New env-var names, YAML keys, or GitHub secret names.
- Changing the 200/hour YNAB policy, adding a global request budget, or pacing successful requests (QA cleanup `QA_CLEANUP_PACING_MS` stays as-is).
- Sharing one CSV across different YNAB accounts (parent vs child still use distinct `tokenEnvVarName` values).
- Inventing remaining-count fields that YNAB does not send.
- OAuth refresh-token flows.
- Circumventing limits with tokens the operator does not own.

## Decisions

### 1. Opportunistic 429 metadata, not a fabricated remaining counter

**Decision:** On every 429, inspect body JSON and headers. Do not assume remaining-count fields exist.

Parse, case-insensitive:

- Body: documented `error.id` / `error.name` / `error.detail`; also numeric/string fields named like `remaining`, `reset`, `limit`, `retry_after` if present under `error` or the root.
- Headers: existing `Retry-After` (delta-seconds or HTTP-date); existing reset-epoch names; `X-Rate-Limit` if it reappears (historical `used/limit`); `RateLimit-Remaining` / `X-RateLimit-Remaining` / `X-Rate-Limit-Remaining`; `RateLimit-Limit` / `X-RateLimit-Limit`.

If a usable resume time exists, it drives backoff **after** token rotation is exhausted, using the current one-hour cap. If none exists, keep 5s linear backoff by **backoff-cycle** number.

INFO log SHALL include: HTTP method, resource class, token slot `k of n` (1-based, never the token), remaining if parsed, retry/reset duration if parsed, the **names** of rate-limit headers that were present, and an explicit `no remaining/reset metadata` when nothing usable was found. WARN remains for the actual sleep.

**Alternatives considered:** Trust docs and ignore headers (rejects Justin’s “there might be remaining/reset info”). Require a live 429 capture before coding (apply can confirm; offline tests must still cover present and absent metadata).

### 2. CSV parsing at load time, list inside the HTTP client

**Decision:** Split env/file values on comma, trim whitespace, drop empty segments, de-duplicate **preserving first-seen order**. Zero tokens after parse is a startup error naming the env var, not a silent skip.

`YnabHttpClient` and `YnabQaClient` accept a list (existing single-string constructors wrap a one-element list for tests). `YnabQaClient` MUST stop treating any whitespace as invalid so `token-a, token-b` works.

GitHub Actions secret **names** stay the same; secret **values** may be CSV.

**Alternatives considered:** New `tokenEnvVarNames` YAML lists (user asked for CSV on existing env vars). Semicolon separators (comma is what was requested).

### 3. Rebuild the request when the token changes

**Decision:** Do not reuse a pre-built `HttpRequest` / `urllib.request.Request` after rotation. Authorization is baked into the request object today. Rebuild method, path, body, and headers with the new Bearer token. Body bytes stay identical so write identity / manifest / import_id behavior is unchanged.

### 4. Per-call rotation, sticky success, sleep only after a full token cycle

**Decision:** Each client holds an ordered token list and a sticky current index (starts at 0).

For one logical call:

1. Send with the current token.
2. Non-429: return; keep that index for later calls.
3. 429 and unused tokens remain: INFO-log, advance to the next unused token, retry immediately (no sleep). Token switches do **not** count toward `maxRateLimitRetries`.
4. 429 and every token has been tried this call: INFO-log exhaustion; compute wait as the **minimum** positive parsed delay among this cycle’s 429s (soonest token might recover), else linear fallback; cap at one hour; WARN-log and sleep; increment backoff-cycle count; if `maxRateLimitRetries` is exhausted, fail as today; otherwise clear the per-call exhausted set, leave the sticky index on the token with the soonest reset if known else index 0, and retry.

**Alternatives considered:** Round-robin every request (burns all tokens evenly, worse for a preferred PAT). Sleep on the first 429 even when other tokens exist (today’s behavior, rejected). Wait the **maximum** delay (over-sleeps if one token recovers sooner).

### 5. Same algorithm in Groovy and Python

**Decision:** Port the same parse / log / rotate / backoff rules into `YnabQaClient._request`. `load_tokens` continues to return raw env strings; the client splits. Offline tests cover both stacks.

### 6. Secrets and tests

**Decision:** Errors, INFO/WARN, and QA evidence MUST NOT contain token values, Authorization, URLs, or plan/transaction IDs. Offline tests MUST assert rotation without sleep, sleep after all tokens 429, CSV parse, redaction, and header/body metadata logging. Finite `maxRateLimitRetries` counts backoff sleeps only.

## Risks / Trade-offs

- **[YNAB 429 may carry no remaining/reset fields]** → Log absence at INFO; keep header-driven + linear fallback. Apply may confirm a live 429; do not block implementation on undocumented fields.
- **[Multiple PATs increase hourly quota for one account]** → Operator-owned personal tokens only; each token still has its own 200/hour window. Do not add a shared limiter that undoes the requested rotation.
- **[Sticky token plus extra writes after 429]** → Write-attempt telemetry records each HTTP send, including rotations. Update tests; retrying the same body preserves idempotency.
- **[CSV with spaces vs QA whitespace check]** → Relax QA constructor; trim per segment.
- **[Logging remaining/reset at INFO leaks operational detail, not secrets]** → Slot index only; never token prefixes.
- **[Soonest-reset wait may retry a still-limited token]** → Next send 429s again and rotates; worst case one extra request per cycle.

## Migration Plan

1. Implement parse + INFO + CSV + rotation in Groovy with `YnabHttpClientSpec` coverage.
2. Wire `RecordAllowance` and `ParentChildBudgetSyncer` token loading.
3. Mirror in `YnabQaClient` / `load_tokens` consumers and QA unit tests.
4. Update docs/examples so CSV is documented and single-token values still work.
5. Verify with `./gradlew test` and QA Python unit tests. Live `qaAutomated` remains opt-in.

Rollback: revert the change; single-token env values keep working on old and new code.

## Open Questions

None for proposal scope. Live 429 header/body shape is confirmed during apply by inspection, not by inventing fields now.
