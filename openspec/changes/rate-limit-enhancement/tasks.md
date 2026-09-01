## 1. Production HTTP client: 429 inspection and INFO

- [x] 1.1 Extend `YnabHttpClient` to parse 429 JSON body and the remaining/reset/limit headers listed in the spec, in addition to existing `Retry-After` and reset-epoch handling.
- [x] 1.2 Emit a redacted INFO log on every 429 (method, resource class, token slot `k of n`, remaining/reset if parsed, header names, or `no remaining/reset metadata`). Keep WARN for actual sleeps. Never log tokens, Authorization, URLs, or IDs.
- [x] 1.3 Add/adjust `YnabHttpClientSpec` coverage for documented body-only 429s, `Retry-After`, remaining headers, and redacted INFO/error text.

## 2. Production HTTP client: CSV tokens and rotation

- [x] 2.1 Add CSV token parsing (comma split, trim, drop empties, de-dupe preserving order) and accept a token list on `YnabHttpClient`, keeping the single-string constructor as a one-token list.
- [x] 2.2 Rebuild the HTTP request Authorization header on token switch. On 429, rotate to the next unused token immediately with no sleep; sticky-success afterward.
- [x] 2.3 Sleep only after every token 429s the same call, using min positive parsed delay or linear fallback, one-hour cap. Count `maxRateLimitRetries` as backoff sleeps only. Do not rotate on non-429 4xx.
- [x] 2.4 Wire `RecordAllowance` (`YNAB_ACCESS_TOKEN`) and `ParentChildBudgetSyncer.resolveRequiredToken` so each existing env var may be CSV.
- [x] 2.5 Extend Groovy tests for rotation without sleep, sleep after all tokens 429, `maxRateLimitRetries` ignoring rotations, CSV with spaces, blank CSV startup failure, 401-does-not-rotate, and write-attempt telemetry per send.

## 3. QA client and token loading

- [x] 3.1 Parse CSV tokens in `YnabQaClient` (relax the current “no whitespace in token” check so `token-a, token-b` works). Rebuild urllib requests when rotating.
- [x] 3.2 Mirror production 429 INFO, rotation-before-sleep, all-tokens-then-backoff, and redaction rules in `qa/lib/ynab_qa_client.py`.
- [x] 3.3 Keep `load_tokens` env-var names unchanged; allow CSV values from env and `tokens.txt`.
- [x] 3.4 Update `qa/tests/test_qa_helpers.py`, `qa/tests/test_qa_ci.py`, and any other QA tests that assume a single token or reject comma/whitespace.

## 4. Docs

- [x] 4.1 Document CSV token syntax for `YNAB_ACCESS_TOKEN` and sync `tokenEnvVarName` in `README.md`, `QUICK_START.md`, and `CONFIGURATION.md` without embedding real tokens.
- [x] 4.2 Document the same for QA env vars / `tokens.txt` in `qa/SETUP.md`, `qa/README.md`, and `qa/config/tokens.txt.example`.

## 5. Verification

- [x] 5.1 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew test`.
- [x] 5.2 Run the repo’s QA Python unit tests (the existing `qa/tests` invocation used by this repo, not live YNAB).
- [x] 5.3 Run `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 && ./gradlew testAll`.
- [ ] 5.4 Do not treat live `qaAutomated` as required for this change; if a live 429 is observed during optional QA, record whether remaining/reset headers or body fields were actually present and adjust parsers only to match reality (do not invent fields).
