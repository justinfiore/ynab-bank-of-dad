## ADDED Requirements

### Requirement: HTTP 429 responses SHALL be inspected for remaining and reset metadata
Production `YnabHttpClient` and QA `YnabQaClient` SHALL inspect each HTTP 429 response body and headers for remaining-request and reset/retry information. They SHALL NOT assume YNAB always sends remaining-count fields. They SHALL parse, when present and usable, case-insensitive `Retry-After` (positive integer seconds or HTTP-date), reset-epoch headers `RateLimit-Reset`, `X-RateLimit-Reset`, and `X-Rate-Limit-Reset`, remaining/limit headers `RateLimit-Remaining`, `X-RateLimit-Remaining`, `X-Rate-Limit-Remaining`, `RateLimit-Limit`, `X-RateLimit-Limit`, and `X-Rate-Limit`, and JSON body fields under `error` or the root named `remaining`, `reset`, `limit`, or `retry_after`. Documented YNAB 429 bodies that contain only `error.id`, `error.name`, and `error.detail` SHALL be treated as having no remaining/reset metadata.

#### Scenario: Documented 429 body with no remaining fields
- **WHEN** YNAB returns HTTP 429 with JSON `error.id` `429`, `error.name` `too_many_requests`, and `error.detail` `Too many requests` and no usable remaining/reset headers
- **THEN** the client SHALL treat remaining/reset metadata as absent
- **AND** it SHALL still classify the response as HTTP 429 for rotation and backoff

#### Scenario: Retry-After and reset headers remain usable
- **WHEN** a 429 includes a usable `Retry-After` or reset-epoch header already supported by the client
- **THEN** that value SHALL be available to the backoff delay calculation after token rotation for the call is exhausted
- **AND** a single wait SHALL remain capped at one hour

### Requirement: Remaining and reset information SHALL be logged at INFO without secrets
On each HTTP 429, production and QA clients SHALL emit an INFO log that includes HTTP method, resource class, token slot as `k of n` (1-based), remaining if parsed, retry or reset delay if parsed, the names of rate-limit headers that were present, and `no remaining/reset metadata` when none was usable. INFO, WARN, exceptions, and QA evidence SHALL NOT include access token values, Authorization header values, request URLs, plan IDs, or transaction IDs.

#### Scenario: INFO log on 429 without metadata
- **WHEN** a 429 has no usable remaining/reset metadata
- **THEN** an INFO log SHALL include the method, resource class, token slot `k of n`, and `no remaining/reset metadata`
- **AND** the log SHALL NOT contain the token value

#### Scenario: INFO log on 429 with Retry-After
- **WHEN** a 429 includes `Retry-After: 3`
- **THEN** an INFO log SHALL include the parsed retry delay
- **AND** the log SHALL NOT contain Authorization header values

### Requirement: Access token environment values SHALL accept a CSV of tokens
`YNAB_ACCESS_TOKEN`, each sync `tokenEnvVarName` (including `YNAB_PARENT_TOKEN` / child names as configured), and QA `PARENT_ACCESS_TOKEN`, `JORSTEN_JR_ACCESS_TOKEN`, `BORSTEN_ACCESS_TOKEN`, and `THORSTEN_ACCESS_TOKEN` (environment or `tokens.txt`) SHALL accept either a single token or a comma-separated list. Parsing SHALL split on comma, trim whitespace, drop empty segments, and de-duplicate while preserving first-seen order. A missing env var or a value that yields zero tokens SHALL fail at startup with an error that names the env var and SHALL NOT log token material. A value with no comma SHALL keep current single-token behavior. Env-var names SHALL NOT change.

#### Scenario: Single token still works
- **WHEN** `YNAB_ACCESS_TOKEN` is set to one token with no comma
- **THEN** the allowance HTTP client SHALL authenticate with that one token

#### Scenario: CSV tokens with spaces
- **WHEN** `PARENT_ACCESS_TOKEN` is `token-a, token-b`
- **THEN** the QA client SHALL treat that as two tokens `token-a` and `token-b`
- **AND** it SHALL NOT reject the value solely because of whitespace around the comma

#### Scenario: Blank CSV is an error
- **WHEN** `YNAB_ACCESS_TOKEN` is `, ,`
- **THEN** startup SHALL fail with an error indicating `YNAB_ACCESS_TOKEN` must be set
- **AND** the error SHALL NOT contain a raw token value

### Requirement: A 429 SHALL rotate to the next token before sleeping
When multiple tokens are configured for a given YNAB account, production and QA clients SHALL, on HTTP 429, switch to the next unused token for that account and retry the same HTTP method, path, and body immediately without sleeping. The Authorization header SHALL be rebuilt for the new token. Token rotation SHALL NOT increment `maxRateLimitRetries`. After a non-429 response, the client SHALL keep using that token for later calls until it receives HTTP 429.

#### Scenario: Second token succeeds without sleep
- **WHEN** a client is configured with tokens `t1,t2` and the request with `t1` returns HTTP 429 and the request with `t2` returns HTTP 200
- **THEN** the client SHALL retry once with `t2` without sleeping
- **AND** the retried request SHALL use the same method, path, and body
- **AND** later calls SHALL use `t2` until it returns HTTP 429

#### Scenario: Rotation does not count as a backoff retry
- **WHEN** `maxRateLimitRetries` is `0` and two tokens are configured and the first token returns HTTP 429 and the second returns HTTP 200
- **THEN** the client SHALL succeed
- **AND** it SHALL NOT fail for exhausting rate-limit retries

### Requirement: Backoff sleep SHALL start only after every token returns 429
When every configured token for that account has returned HTTP 429 for the same logical call, the client SHALL enter the existing backoff sleep, then retry. The wait SHALL be the minimum positive parsed remaining/reset/`Retry-After` delay among that cycle’s 429 responses, or the existing 5-second linear fallback by backoff-cycle number if no usable delay exists, capped at one hour. WARN SHALL record that sleep. If `maxRateLimitRetries` is non-null, it SHALL count backoff sleeps only; exhausting it SHALL fail with a redacted status-429 error as today. Other 4xx responses SHALL still fail immediately without rotation.

#### Scenario: All tokens 429 then sleep
- **WHEN** a client is configured with tokens `t1,t2` and both return HTTP 429 with no usable resume header on the first logical call
- **THEN** the client SHALL send with `t1` then `t2` without sleeping between them
- **AND** it SHALL then sleep the linear fallback delay for backoff cycle 1
- **AND** it SHALL then retry

#### Scenario: Non-429 4xx does not rotate
- **WHEN** the current token returns HTTP 401
- **THEN** the client SHALL fail immediately
- **AND** it SHALL NOT try the next token
- **AND** it SHALL NOT sleep
