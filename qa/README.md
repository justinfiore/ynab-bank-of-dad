# Reconciliation QA evidence tooling

This phase provides an offline, read-only provisioning inspector. It compares a manually prepared discovery snapshot with the required parent categories and child accounts, then writes `missing-provisioning.json`. It does not call YNAB, does not read or manage access tokens, does not run the syncer, and does not perform live writes.

## Safe setup

1. Copy `qa/config/qa-sync.yaml.example` to `qa/config/qa-sync.yaml`.
2. Replace each `fullId` placeholder with the complete immutable plan UUID for the exact configured display name. Never enter only a known suffix and never construct or guess an ID.
3. Copy `qa/config/discovered-provisioning.json.example` to `qa/discovered-provisioning.json`.
4. Using information gathered manually outside this tool, enter the same exact budget display names and full IDs, the parent category names, and each child's account names. Do not put credentials in either file.

The required parent categories are `QA Jorsten Jr Silver`, `QA Jorsten Jr Bronze`, `QA Borsten Silver`, `QA Borsten Bronze`, `QA Thorsten Silver`, `QA Thorsten Bronze`, `QA Unmapped`, and `QA Transfer Clearing`. Every child plan requires accounts named exactly `Silver` and `Bronze`.

## Exact invocation

Run the inspector with Gradle offline mode so dependency resolution cannot make a network request:

```bash
./gradlew qaInspectProvisioning --offline --args="--config qa/config/qa-sync.yaml --discovery qa/discovered-provisioning.json --artifact qa/artifacts/missing-provisioning.json"
```

The command creates only the caller-selected local artifact. `complete: true` means all required names were present. Otherwise, `missingParentCategories` and each child's `missingAccounts` list are the provisioning gaps. The inspector never creates a category or account.

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
