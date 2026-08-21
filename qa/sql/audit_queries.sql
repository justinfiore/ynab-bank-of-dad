-- Run only against an evidence copy opened read-only by qa/lib/run_capture.py.
SELECT * FROM schema_versions;
SELECT * FROM sync_runs;
SELECT * FROM source_entities;
SELECT * FROM child_mirrors;
SELECT * FROM sync_operations;
SELECT * FROM operation_attempts;
