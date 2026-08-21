# Reconciliation QA evidence tooling

This directory contains reconciliation evidence tooling plus a narrowly scoped,
fail-closed category provisioning CLI. The only authorized provisioning target
is the existing disposable QA parent plan named `Jorsten's Plan`. A family plan
is never an authorized target. Child-plan writes must always remain zero.

The current public category API supports these routes:

- `POST /v1/plans/{plan_id}/category_groups` creates a category group.
- `POST /v1/plans/{plan_id}/categories` creates a category.
- `PATCH /v1/plans/{plan_id}/categories/{category_id}` renames a category.

This tool uses only the two POST routes. It has no PATCH/rename path and never
changes or removes an existing category. Public API account creation is not supported,
so this repository has no account-mutation path. The already
observed `Silver` and `Bronze` accounts in each of the three child plans are
verified prerequisites for later reconciliation QA, not provisioning targets.

## Safe setup

1. Copy `qa/config/qa-sync.yaml.example` to `qa/config/qa-sync.yaml`.
2. Replace each `fullId` placeholder with the complete immutable plan UUID for the exact configured display name. Never enter only a known suffix and never construct or guess an ID.
3. Copy `qa/config/discovered-provisioning.json.example` to `qa/discovered-provisioning.json`.
4. Using information gathered manually outside this tool, enter the same exact budget display names and full IDs, the parent category names, and each child's account names. Do not put credentials in either file.

The required parent categories are `QA Jorsten Jr Silver`, `QA Jorsten Jr Bronze`, `QA Borsten Silver`, `QA Borsten Bronze`, `QA Thorsten Silver`, `QA Thorsten Bronze`, `QA Unmapped`, and `QA Transfer Clearing`. They belong only in the dedicated `BOD Reconciliation QA` group. Every child plan requires the already verified accounts named exactly `Silver` and `Bronze`.

## Exact invocation

Run the inspector with Gradle offline mode so dependency resolution cannot make a network request:

```bash
./gradlew qaInspectProvisioning --offline --args="--config qa/config/qa-sync.yaml --discovery qa/discovered-provisioning.json --artifact qa/artifacts/missing-provisioning.json"
```

The command creates only the caller-selected local artifact. `complete: true` means all required names were present. Otherwise, `missingParentCategories` and each child's `missingAccounts` list are the provisioning gaps. The inspector never creates a category or account.
That offline inspector does not read or manage access tokens and does not perform live writes.

## Category provisioning

`qa/provision_categories.py` always loads the fixed, ignored
`qa/config/qa-sync.yaml`; there is no alternate config argument. It requires
four complete UUID/name pairs and performs a fresh `GET /v1/plans` discovery to
validate every exact pair before reading the parent categories or allowing a
write. It then uses `GET /v1/plans/{plan_id}/categories` to validate the full
group/category structure. IDs are never inferred from suffixes.

Generate the required dry-run artifact first:

```bash
python3 qa/provision_categories.py \
  --dry-run \
  --campaign-id <qa-campaign-id> \
  --provisioning-tag BOD-QA-PROVISION-<unique-tag> \
  --artifact qa/artifacts/<qa-campaign-id>/category-provisioning-dry-run.json
```

The dry run performs GET requests but zero writes. Its deterministic manifest
contains only an optional create for the one dedicated group and creates for
the exact missing subset of the eight required category names. It records zero
child, account, and rename writes. Duplicate, deleted, misplaced, ambiguous, or
malformed target names/groups block the run; unrelated existing categories are
left unchanged.

Review that generated artifact. Live provisioning is refused unless a new
fresh discovery produces an exactly equal manifest, every write matches its
one expected manifest payload, the target remains the exact QA parent, and the
live command has the exact confirmation value:

```bash
QA_CONFIRM_PROVISIONING_MUTATIONS=YES \
python3 qa/provision_categories.py \
  --provision \
  --campaign-id <qa-campaign-id> \
  --provisioning-tag BOD-QA-PROVISION-<unique-tag> \
  --artifact qa/artifacts/<qa-campaign-id>/category-provisioning-dry-run.json \
  --receipt qa/artifacts/<qa-campaign-id>/category-provisioning-receipt.json
```

Do not run the live command against a family plan. The client permits category
creates only for the exact configured `Jorsten's Plan` name/full-ID pair and
requires `QA_CONFIRM_PROVISIONING_MUTATIONS=YES`. Each response must contain
exactly one matching created object. Receipts contain hashes, counts, symbolic
routes, and the distinct local campaign/provisioning tags; category request
bodies contain only API-supported fields because category objects have no memo.
Tokens, headers, full UUIDs, and raw API response bodies are never written to
the artifact or receipt. The tracked JSON examples document the redacted shape
only; always use the freshly generated artifact rather than copying an example.

The code also includes transaction-only fixture and observation helpers, a
fail-closed scenario wrapper, SQLite audit capture, structured receipts, and a
self-contained static HTML renderer. The wrapper requires exact allowlist
evidence for dry runs. Live mode additionally requires completed provisioning,
a passing scenario dry run, a non-empty expected mutation manifest, and exactly
`QA_CONFIRM_LIVE_MUTATIONS=YES`. Captured output is redacted using every
token/authorization environment value before it reaches disk.

Run the harness unit tests with:

```bash
python3 -m unittest discover -s qa/tests -v
```

Perform a fresh fail-closed, read-only API discovery after loading the four
test-token environment variables locally:

```bash
python3 qa/discover_read_only.py \
  --config qa/config/qa-sync.yaml \
  --campaign-root qa/artifacts/<campaign-id>
```

The command validates every exact display-name/full-UUID pair before reading
categories, accounts, or transactions. It issues GET requests only, retains
only `BOD QA`-tagged transactions, and writes no UUIDs, resource IDs, tokens,
headers, or raw responses to its evidence.

Render an existing campaign with:

```bash
python3 qa/report/render_report.py qa/artifacts/<campaign-id>
```

After receipts, copied test reports, report HTML, and screenshots are present,
load the local test-token environment and finalize the portable bundle:

```bash
python3 qa/finalize_evidence.py qa/artifacts/<campaign-id>
```

Finalization rejects raw token values, Authorization header values, full UUIDs
outside ignored internal config, incomplete matrices, and nonzero blocked-campaign
write counts. It writes the summary, per-file checksums, ZIP, and ZIP checksum.

`qa/artifacts/`, the completed config, raw discovery snapshots, tokens, state
databases, and campaign state are Gitignored. Report receipts must use one of
`PASS`, `FAIL`, `BLOCKED`, or `NOT_RUN`; an omitted scenario is a report defect.
