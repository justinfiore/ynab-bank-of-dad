package ynabbankofdad.sync.state

import groovy.json.JsonOutput
import ynabbankofdad.sync.model.ChildTransactionPlan

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface SyncStateRepository {
    void initialize()
    long startRun(boolean dryRun, int pollingIntervalSeconds, String sourceBudgetId)
    void finishRun(long runId, String status, String errorSummary)
    long recordSourceEvent(ChildTransactionPlan plan)
    long recordMapping(long sourceEventId, ChildTransactionPlan plan, String targetBudgetId, String accountId)
    void recordAppliedTransaction(long mappingId, long runId, String targetBudgetId, String createdChildTransactionId, String status, String failureReason, boolean dryRun)
    boolean hasAppliedIdempotencyKey(String idempotencyKey)
    Integer getCursor(String key)
    void setCursor(String key, Integer value)
}

interface ReconciliationOperationStateRepository {
    List<ReconciliationOperation> findReadyOperations(int limit)
    ReconciliationOperationAttempt findSuccessfulOperationAttempt(long operationId)
    SourceEntityKey findSourceEntityKey(long sourceEntityId)
    void recordOperationAttempt(long operationId, String outcome, String failureReason, String returnedChildTransactionId)
    void markOperationRetryable(long operationId)
    void completeCreateOperation(long operationId, long sourceEntityId, Long oldMirrorId,
                                 String targetBudgetId, String direction, String childTransactionId,
                                 String targetAccountId, String payloadHash, boolean recreation)
    void completeUpdateOperation(long operationId, long mirrorId, String targetAccountId, String payloadHash)
    void completeDeleteOperation(long operationId, Long mirrorId)
}

interface ReconciliationSyncStateRepository extends ReconciliationOperationStateRepository {
    boolean reconciliationSchemaAvailable()
    long upsertSourceEntity(SourceEntityKey key)
    long appendSourceRevision(long sourceEntityId, String revisionHash, String normalizedJson,
                              Integer serverKnowledge, Long ingestionBatchId)
    long createIngestionBatch(String batchKey, String sourceKind, Integer serverKnowledge)
    boolean completeIngestionBatchIfReady(long batchId)
    List<ChildMirrorState> findMirrorsForParent(String sourceBudgetId, String parentTransactionId,
                                                 boolean includeInactive)
    List<ChildMirrorState> findMirrorsForSource(SourceEntityKey source, boolean includeInactive)
    List<SourceEntityKey> findSourceEntities(String sourceBudgetId, SourceEntityType type)
    void setSourceLifecycle(long sourceEntityId, String lifecycleStatus)
    long createOperation(ReconciliationOperationIntent intent)
    List<ReconciliationOperation> findPendingMigrationCleanupOperations()
    MigrationProjection projectLegacyMigration()
}

class DryRunSyncStateRepository implements SyncStateRepository, ReconciliationSyncStateRepository {
    private final SyncStateStore existingState
    private final Path databasePath

    DryRunSyncStateRepository(SyncStateStore existingState, String databasePath) {
        this.existingState = existingState
        this.databasePath = Paths.get(databasePath)
    }

    void initialize() {}
    long startRun(boolean dryRun, int pollingIntervalSeconds, String sourceBudgetId) { -1L }
    void finishRun(long runId, String status, String errorSummary) {}
    long recordSourceEvent(ChildTransactionPlan plan) { -1L }
    long recordMapping(long sourceEventId, ChildTransactionPlan plan, String targetBudgetId, String accountId) { -1L }
    void recordAppliedTransaction(long mappingId, long runId, String targetBudgetId, String createdChildTransactionId, String status, String failureReason, boolean dryRun) {}
    boolean hasAppliedIdempotencyKey(String idempotencyKey) {
        Files.exists(databasePath) && existingState.hasAppliedIdempotencyKey(idempotencyKey)
    }
    Integer getCursor(String key) {
        Files.exists(databasePath) ? existingState.getCursor(key) : null
    }
    void setCursor(String key, Integer value) {}
    boolean reconciliationSchemaAvailable() {
        Files.exists(databasePath) && existingState.reconciliationSchemaAvailable()
    }
    List<ChildMirrorState> findMirrorsForParent(String sourceBudgetId, String parentTransactionId,
                                                 boolean includeInactive = false) {
        reconciliationSchemaAvailable() ?
            existingState.findMirrorsForParent(sourceBudgetId, parentTransactionId, includeInactive) : []
    }
    List<ChildMirrorState> findMirrorsForSource(SourceEntityKey source, boolean includeInactive = false) {
        reconciliationSchemaAvailable() ? existingState.findMirrorsForSource(source, includeInactive) : []
    }
    List<SourceEntityKey> findSourceEntities(String sourceBudgetId, SourceEntityType type) {
        reconciliationSchemaAvailable() ? existingState.findSourceEntities(sourceBudgetId, type) : []
    }
    List<ReconciliationOperation> findPendingMigrationCleanupOperations() {
        reconciliationSchemaAvailable() ? existingState.findPendingMigrationCleanupOperations() : []
    }
    MigrationProjection projectLegacyMigration() { existingState.projectLegacyMigration() }

    long upsertSourceEntity(SourceEntityKey key) { throw dryRunWrite() }
    long appendSourceRevision(long sourceEntityId, String revisionHash, String normalizedJson,
                              Integer serverKnowledge, Long ingestionBatchId) { throw dryRunWrite() }
    long createIngestionBatch(String batchKey, String sourceKind, Integer serverKnowledge) { throw dryRunWrite() }
    boolean completeIngestionBatchIfReady(long batchId) { throw dryRunWrite() }
    void setSourceLifecycle(long sourceEntityId, String lifecycleStatus) { throw dryRunWrite() }
    long createOperation(ReconciliationOperationIntent intent) { throw dryRunWrite() }
    List<ReconciliationOperation> findReadyOperations(int limit) { [] }
    ReconciliationOperationAttempt findSuccessfulOperationAttempt(long operationId) { null }
    SourceEntityKey findSourceEntityKey(long sourceEntityId) { existingState.findSourceEntityKey(sourceEntityId) }
    void recordOperationAttempt(long operationId, String outcome, String failureReason, String childId) { throw dryRunWrite() }
    void markOperationRetryable(long operationId) { throw dryRunWrite() }
    void completeCreateOperation(long operationId, long sourceEntityId, Long oldMirrorId,
                                 String targetBudgetId, String direction, String childTransactionId,
                                 String targetAccountId, String payloadHash, boolean recreation) { throw dryRunWrite() }
    void completeUpdateOperation(long operationId, long mirrorId, String targetAccountId, String payloadHash) { throw dryRunWrite() }
    void completeDeleteOperation(long operationId, Long mirrorId) { throw dryRunWrite() }

    private static IllegalStateException dryRunWrite() {
        new IllegalStateException('Dry-run reconciliation attempted to write sync state')
    }
}

class SyncStateStore implements SyncStateRepository, ReconciliationSyncStateRepository {
    static final int CURRENT_SCHEMA_VERSION = 2
    final String databasePath
    private final Closure migrationStepHook

    SyncStateStore(String databasePath) {
        this(databasePath, null)
    }

    SyncStateStore(String databasePath, Closure migrationStepHook) {
        this.databasePath = databasePath
        this.migrationStepHook = migrationStepHook
    }

    void initialize() {
        Path dbPath = Paths.get(databasePath)
        if (dbPath.parent != null) {
            Files.createDirectories(dbPath.parent)
        }
        withConnection { Connection connection ->
            connection.autoCommit = false
            try {
                int version = schemaVersion(connection)
                if (version > CURRENT_SCHEMA_VERSION) {
                    throw new IllegalStateException("State schema version ${version} is newer than supported version ${CURRENT_SCHEMA_VERSION}")
                }
                if (version < 1) {
                    migrateLegacySchema(connection)
                    migrationStepHook?.call(1, connection)
                    recordSchemaVersion(connection, 1)
                }
                if (version < 2) {
                    migrateReconciliationSchema(connection)
                    migrationStepHook?.call(2, connection)
                    recordSchemaVersion(connection, 2)
                }
                connection.commit()
            } catch (Throwable failure) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    boolean reconciliationSchemaAvailable() {
        if (!Files.exists(Paths.get(databasePath))) {
            return false
        }
        withConnection { Connection connection -> tableExists(connection, 'sync_operations') }
    }

    private static void migrateLegacySchema(Connection connection) {
        connection.createStatement().execute('''
            CREATE TABLE IF NOT EXISTS schema_versions (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL
            )
        ''')
        connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at TEXT NOT NULL,
                    completed_at TEXT NULL,
                    dry_run INTEGER NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('running', 'succeeded', 'partial', 'failed')),
                    error_summary TEXT NULL,
                    polling_interval_seconds INTEGER NULL,
                    source_budget_id TEXT NOT NULL
                )
        ''')
        connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS source_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_budget_id TEXT NOT NULL,
                    event_type TEXT NOT NULL CHECK(event_type IN ('transaction', 'subtransaction', 'money_movement')),
                    parent_transaction_id TEXT NULL,
                    parent_subtransaction_id TEXT NULL,
                    money_movement_id TEXT NULL,
                    money_movement_group_id TEXT NULL,
                    event_date TEXT NULL,
                    ynab_server_knowledge INTEGER NULL,
                    fingerprint TEXT NOT NULL UNIQUE,
                    raw_summary_json TEXT NULL,
                    created_at TEXT NOT NULL,
                    CHECK(
                        (event_type = 'transaction' AND parent_transaction_id IS NOT NULL AND parent_subtransaction_id IS NULL AND money_movement_id IS NULL)
                        OR (event_type = 'subtransaction' AND parent_transaction_id IS NOT NULL AND parent_subtransaction_id IS NOT NULL AND money_movement_id IS NULL)
                        OR (event_type = 'money_movement' AND parent_transaction_id IS NULL AND parent_subtransaction_id IS NULL AND money_movement_id IS NOT NULL)
                    )
                )
        ''')
        connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_mappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_event_id INTEGER NOT NULL,
                    target_budget_id TEXT NOT NULL,
                    target_child_key TEXT NOT NULL,
                    target_mapping_key TEXT NULL,
                    target_account_name TEXT NULL,
                    target_account_id TEXT NULL,
                    direction TEXT NOT NULL CHECK(direction IN ('inflow', 'outflow')),
                    planned_amount INTEGER NOT NULL,
                    planned_date TEXT NOT NULL,
                    planned_payee_name TEXT NULL,
                    planned_memo TEXT NULL,
                    planned_category_id TEXT NULL,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    last_planned_at TEXT NOT NULL,
                    FOREIGN KEY(source_event_id) REFERENCES source_events(id) ON DELETE CASCADE
                )
        ''')
        connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS applied_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_mapping_id INTEGER NOT NULL,
                    sync_run_id INTEGER NOT NULL,
                    target_budget_id TEXT NOT NULL,
                    created_child_transaction_id TEXT NULL,
                    applied_at TEXT NULL,
                    status TEXT NOT NULL CHECK(status IN ('applied', 'failed')),
                    failure_reason TEXT NULL,
                    dry_run INTEGER NOT NULL,
                    FOREIGN KEY(sync_mapping_id) REFERENCES sync_mappings(id) ON DELETE CASCADE,
                    FOREIGN KEY(sync_run_id) REFERENCES sync_runs(id) ON DELETE CASCADE
                )
        ''')
        connection.createStatement().execute('''
                CREATE TABLE IF NOT EXISTS sync_cursors (
                    key TEXT PRIMARY KEY,
                    value_text TEXT NULL,
                    value_integer INTEGER NULL,
                    updated_at TEXT NOT NULL
                )
        ''')
        ensureColumn(connection, 'sync_mappings', 'target_mapping_key', 'TEXT NULL')
        ensureColumn(connection, 'sync_mappings', 'target_account_name', 'TEXT NULL')
    }

    private static void migrateReconciliationSchema(Connection connection) {
        connection.createStatement().execute('''
            CREATE TABLE source_entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                identity_key TEXT NOT NULL UNIQUE,
                source_budget_id TEXT NOT NULL,
                entity_type TEXT NOT NULL CHECK(entity_type IN ('transaction', 'subtransaction', 'money_movement')),
                parent_transaction_id TEXT NULL,
                parent_subtransaction_id TEXT NULL,
                money_movement_id TEXT NULL,
                lifecycle_status TEXT NOT NULL DEFAULT 'active' CHECK(lifecycle_status IN ('active', 'deleted', 'unconfirmed')),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                CHECK(
                    (entity_type = 'transaction' AND parent_transaction_id IS NOT NULL AND parent_subtransaction_id IS NULL AND money_movement_id IS NULL)
                    OR (entity_type = 'subtransaction' AND parent_transaction_id IS NOT NULL AND parent_subtransaction_id IS NOT NULL AND money_movement_id IS NULL)
                    OR (entity_type = 'money_movement' AND parent_transaction_id IS NULL AND parent_subtransaction_id IS NULL AND money_movement_id IS NOT NULL)
                )
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE source_revisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_entity_id INTEGER NOT NULL,
                revision_hash TEXT NOT NULL,
                normalized_json TEXT NOT NULL,
                ynab_server_knowledge INTEGER NULL,
                ingestion_batch_id INTEGER NULL,
                observed_at TEXT NOT NULL,
                UNIQUE(source_entity_id, revision_hash),
                FOREIGN KEY(source_entity_id) REFERENCES source_entities(id),
                FOREIGN KEY(ingestion_batch_id) REFERENCES ingestion_batches(id)
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE ingestion_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_key TEXT NOT NULL UNIQUE,
                source_kind TEXT NOT NULL CHECK(source_kind IN ('transaction_delta', 'money_movement_snapshot', 'migration')),
                ynab_server_knowledge INTEGER NULL,
                status TEXT NOT NULL CHECK(status IN ('pending', 'completed')),
                created_at TEXT NOT NULL,
                completed_at TEXT NULL
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE child_mirrors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_entity_id INTEGER NOT NULL,
                target_budget_id TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('inflow', 'outflow')),
                child_transaction_id TEXT NOT NULL,
                target_account_id TEXT NULL,
                authoritative_payload_hash TEXT NULL,
                status TEXT NOT NULL CHECK(status IN ('active', 'deleted', 'replaced', 'missing', 'superseded')),
                created_at TEXT NOT NULL,
                ended_at TEXT NULL,
                FOREIGN KEY(source_entity_id) REFERENCES source_entities(id)
            )
        ''')
        connection.createStatement().execute('''
            CREATE UNIQUE INDEX one_active_child_mirror
            ON child_mirrors(source_entity_id, target_budget_id, direction)
            WHERE status = 'active'
        ''')
        connection.createStatement().execute('''
            CREATE TABLE sync_operations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_key TEXT NOT NULL UNIQUE,
                ingestion_batch_id INTEGER NULL,
                source_entity_id INTEGER NOT NULL,
                child_mirror_id INTEGER NULL,
                operation_sequence INTEGER NOT NULL CHECK(operation_sequence >= 0),
                operation_type TEXT NOT NULL CHECK(operation_type IN ('create', 'update', 'delete')),
                target_budget_id TEXT NOT NULL,
                child_transaction_id TEXT NULL,
                payload_json TEXT NULL,
                payload_hash TEXT NULL,
                depends_on_operation_id INTEGER NULL,
                status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending', 'applied', 'retryable_failed')),
                created_at TEXT NOT NULL,
                completed_at TEXT NULL,
                FOREIGN KEY(ingestion_batch_id) REFERENCES ingestion_batches(id),
                FOREIGN KEY(source_entity_id) REFERENCES source_entities(id),
                FOREIGN KEY(child_mirror_id) REFERENCES child_mirrors(id),
                FOREIGN KEY(depends_on_operation_id) REFERENCES sync_operations(id),
                CHECK(operation_type = 'create' OR child_transaction_id IS NOT NULL)
            )
        ''')
        connection.createStatement().execute('''
            CREATE INDEX ready_sync_operations
            ON sync_operations(status, operation_sequence, id)
        ''')
        connection.createStatement().execute('''
            CREATE TABLE operation_attempts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sync_operation_id INTEGER NOT NULL,
                attempted_at TEXT NOT NULL,
                outcome TEXT NOT NULL CHECK(outcome IN ('applied', 'failed', 'already_complete')),
                failure_reason TEXT NULL,
                returned_child_transaction_id TEXT NULL,
                FOREIGN KEY(sync_operation_id) REFERENCES sync_operations(id)
            )
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER source_revisions_are_append_only
            BEFORE UPDATE ON source_revisions BEGIN
                SELECT RAISE(ABORT, 'source revisions are append-only');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER source_revisions_cannot_be_deleted
            BEFORE DELETE ON source_revisions BEGIN
                SELECT RAISE(ABORT, 'source revisions are append-only');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER operation_attempts_are_append_only
            BEFORE UPDATE ON operation_attempts BEGIN
                SELECT RAISE(ABORT, 'operation attempts are append-only');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER operation_attempts_cannot_be_deleted
            BEFORE DELETE ON operation_attempts BEGIN
                SELECT RAISE(ABORT, 'operation attempts are append-only');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER operation_intent_is_immutable
            BEFORE UPDATE ON sync_operations
            WHEN OLD.operation_key IS NOT NEW.operation_key
              OR OLD.ingestion_batch_id IS NOT NEW.ingestion_batch_id
              OR OLD.source_entity_id IS NOT NEW.source_entity_id
              OR OLD.child_mirror_id IS NOT NEW.child_mirror_id
              OR OLD.operation_sequence IS NOT NEW.operation_sequence
              OR OLD.operation_type IS NOT NEW.operation_type
              OR OLD.target_budget_id IS NOT NEW.target_budget_id
              OR OLD.child_transaction_id IS NOT NEW.child_transaction_id
              OR OLD.payload_json IS NOT NEW.payload_json
              OR OLD.payload_hash IS NOT NEW.payload_hash
              OR OLD.depends_on_operation_id IS NOT NEW.depends_on_operation_id
              OR OLD.created_at IS NOT NEW.created_at
            BEGIN
                SELECT RAISE(ABORT, 'operation intent is immutable');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER child_mirror_lineage_cannot_be_deleted
            BEFORE DELETE ON child_mirrors BEGIN
                SELECT RAISE(ABORT, 'child mirror lineage cannot be deleted');
            END
        ''')
        connection.createStatement().execute('''
            CREATE TRIGGER child_mirror_identity_is_immutable
            BEFORE UPDATE ON child_mirrors
            WHEN OLD.source_entity_id IS NOT NEW.source_entity_id
              OR OLD.target_budget_id IS NOT NEW.target_budget_id
              OR OLD.direction IS NOT NEW.direction
              OR OLD.child_transaction_id IS NOT NEW.child_transaction_id
              OR OLD.created_at IS NOT NEW.created_at
            BEGIN
                SELECT RAISE(ABORT, 'child mirror identity is immutable');
            END
        ''')
        backfillLegacyState(connection)
    }

    long upsertSourceEntity(SourceEntityKey key) {
        validateSourceKey(key)
        withConnection { Connection connection ->
            String timestamp = now()
            def statement = connection.prepareStatement('''
                INSERT INTO source_entities(identity_key, source_budget_id, entity_type, parent_transaction_id,
                                            parent_subtransaction_id, money_movement_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(identity_key) DO UPDATE SET updated_at = excluded.updated_at
            ''')
            statement.setString(1, sourceIdentity(key))
            statement.setString(2, key.sourceBudgetId)
            statement.setString(3, key.type.databaseValue)
            statement.setString(4, key.parentTransactionId)
            statement.setString(5, key.parentSubtransactionId)
            statement.setString(6, key.moneyMovementId)
            statement.setString(7, timestamp)
            statement.setString(8, timestamp)
            statement.executeUpdate()
            selectLong(connection, 'SELECT id FROM source_entities WHERE identity_key = ?', sourceIdentity(key))
        }
    }

    long appendSourceRevision(long sourceEntityId, String revisionHash, String normalizedJson,
                              Integer serverKnowledge = null, Long ingestionBatchId = null) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO source_revisions(source_entity_id, revision_hash, normalized_json,
                                             ynab_server_knowledge, ingestion_batch_id, observed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_entity_id, revision_hash) DO NOTHING
            ''')
            statement.setLong(1, sourceEntityId)
            statement.setString(2, revisionHash)
            statement.setString(3, normalizedJson)
            statement.setObject(4, serverKnowledge)
            statement.setObject(5, ingestionBatchId)
            statement.setString(6, now())
            statement.executeUpdate()
            selectLong(connection,
                'SELECT id FROM source_revisions WHERE source_entity_id = ? AND revision_hash = ?',
                sourceEntityId, revisionHash)
        }
    }

    long createIngestionBatch(String batchKey, String sourceKind, Integer serverKnowledge = null) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO ingestion_batches(batch_key, source_kind, ynab_server_knowledge, status, created_at)
                VALUES (?, ?, ?, 'pending', ?)
                ON CONFLICT(batch_key) DO NOTHING
            ''')
            statement.setString(1, batchKey)
            statement.setString(2, sourceKind)
            statement.setObject(3, serverKnowledge)
            statement.setString(4, now())
            statement.executeUpdate()
            selectLong(connection, 'SELECT id FROM ingestion_batches WHERE batch_key = ?', batchKey)
        }
    }

    void completeIngestionBatch(long batchId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE ingestion_batches SET status = 'completed', completed_at = ? WHERE id = ?
            ''')
            statement.setString(1, now())
            statement.setLong(2, batchId)
            statement.executeUpdate()
        }
    }

    boolean completeIngestionBatchIfReady(long batchId) {
        withTransaction { Connection connection ->
            def pending = connection.prepareStatement('''
                SELECT COUNT(*) FROM sync_operations
                WHERE ingestion_batch_id = ? AND status != 'applied'
            ''')
            pending.setLong(1, batchId)
            def result = pending.executeQuery()
            result.next()
            if (result.getInt(1) != 0) {
                return false
            }
            def complete = connection.prepareStatement('''
                UPDATE ingestion_batches SET status = 'completed', completed_at = COALESCE(completed_at, ?)
                WHERE id = ?
            ''')
            complete.setString(1, now())
            complete.setLong(2, batchId)
            complete.executeUpdate()
            true
        } as boolean
    }

    List<ChildMirrorState> findMirrorsForParent(String sourceBudgetId, String parentTransactionId,
                                                 boolean includeInactive = false) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT mirror.*
                FROM child_mirrors mirror
                JOIN source_entities entity ON entity.id = mirror.source_entity_id
                WHERE entity.source_budget_id = ? AND entity.parent_transaction_id = ?
                  AND (? = 1 OR mirror.status = 'active')
                ORDER BY mirror.id
            ''')
            statement.setString(1, sourceBudgetId)
            statement.setString(2, parentTransactionId)
            statement.setInt(3, includeInactive ? 1 : 0)
            childMirrors(statement.executeQuery())
        }
    }

    List<ChildMirrorState> findMirrorsForSource(SourceEntityKey source, boolean includeInactive = false) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT mirror.*
                FROM child_mirrors mirror
                JOIN source_entities entity ON entity.id = mirror.source_entity_id
                WHERE entity.identity_key = ? AND (? = 1 OR mirror.status = 'active')
                ORDER BY mirror.id
            ''')
            statement.setString(1, sourceIdentity(source))
            statement.setInt(2, includeInactive ? 1 : 0)
            childMirrors(statement.executeQuery())
        }
    }

    List<SourceEntityKey> findSourceEntities(String sourceBudgetId, SourceEntityType type) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT * FROM source_entities WHERE source_budget_id = ? AND entity_type = ? ORDER BY identity_key
            ''')
            statement.setString(1, sourceBudgetId)
            statement.setString(2, type.databaseValue)
            def result = statement.executeQuery()
            List<SourceEntityKey> sources = []
            while (result.next()) {
                sources << new SourceEntityKey(result.getString('source_budget_id'), type,
                    result.getString('parent_transaction_id'), result.getString('parent_subtransaction_id'),
                    result.getString('money_movement_id'))
            }
            sources
        }
    }

    void setSourceLifecycle(long sourceEntityId, String lifecycleStatus) {
        if (!(lifecycleStatus in ['active', 'deleted', 'unconfirmed'])) {
            throw new IllegalArgumentException("Unsupported source lifecycle '${lifecycleStatus}'")
        }
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE source_entities SET lifecycle_status = ?, updated_at = ? WHERE id = ?
            ''')
            statement.setString(1, lifecycleStatus)
            statement.setString(2, now())
            statement.setLong(3, sourceEntityId)
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Source entity ${sourceEntityId} does not exist")
            }
        }
    }

    long createOperation(ReconciliationOperationIntent intent) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_operations(operation_key, ingestion_batch_id, source_entity_id, child_mirror_id,
                                            operation_sequence, operation_type, target_budget_id,
                                            child_transaction_id, payload_json, payload_hash,
                                            depends_on_operation_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                ON CONFLICT(operation_key) DO NOTHING
            ''')
            bindOperation(statement, intent)
            statement.setString(12, now())
            statement.executeUpdate()
            long operationId = selectLong(connection,
                'SELECT id FROM sync_operations WHERE operation_key = ?', intent.operationKey)
            ReconciliationOperationIntent persisted = operationIntent(connection, operationId)
            boolean replayedCreate = persisted.operationType == ReconciliationOperationType.CREATE &&
                intent.operationType == ReconciliationOperationType.CREATE &&
                persisted.operationKey == intent.operationKey &&
                persisted.ingestionBatchId == intent.ingestionBatchId &&
                persisted.sourceEntityId == intent.sourceEntityId &&
                persisted.targetBudgetId == intent.targetBudgetId &&
                persisted.childTransactionId == intent.childTransactionId &&
                persisted.payloadJson == intent.payloadJson &&
                persisted.payloadHash == intent.payloadHash
            if (persisted != intent && !replayedCreate) {
                throw new IllegalStateException("Operation key '${intent.operationKey}' already has different intent")
            }
            operationId
        }
    }

    List<ReconciliationOperation> findReadyOperations(int limit = 100) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT operation.*
                FROM sync_operations operation
                LEFT JOIN sync_operations dependency ON dependency.id = operation.depends_on_operation_id
                WHERE operation.status IN ('pending', 'retryable_failed')
                  AND (operation.depends_on_operation_id IS NULL OR dependency.status = 'applied')
                ORDER BY CASE WHEN operation.ingestion_batch_id IS NULL THEN 1 ELSE 0 END,
                         operation.ingestion_batch_id, operation.operation_sequence, operation.id
                LIMIT ?
            ''')
            statement.setInt(1, limit)
            operations(statement.executeQuery())
        }
    }

    List<ReconciliationOperation> findPendingMigrationCleanupOperations() {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT operation.*
                FROM sync_operations operation
                WHERE operation.ingestion_batch_id IS NULL
                  AND operation.operation_type = 'delete'
                  AND operation.status IN ('pending', 'retryable_failed')
                ORDER BY operation.operation_sequence, operation.id
            ''')
            operations(statement.executeQuery())
        }
    }

    ReconciliationOperationAttempt findSuccessfulOperationAttempt(long operationId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT * FROM operation_attempts
                WHERE sync_operation_id = ? AND outcome IN ('applied', 'already_complete')
                ORDER BY id DESC LIMIT 1
            ''')
            statement.setLong(1, operationId)
            def result = statement.executeQuery()
            result.next() ? new ReconciliationOperationAttempt(
                result.getLong('id'), result.getLong('sync_operation_id'), result.getString('outcome'),
                result.getString('failure_reason'), result.getString('returned_child_transaction_id'),
                result.getString('attempted_at')) : null
        }
    }

    SourceEntityKey findSourceEntityKey(long sourceEntityId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT * FROM source_entities WHERE id = ?')
            statement.setLong(1, sourceEntityId)
            def result = statement.executeQuery()
            if (!result.next()) {
                throw new IllegalStateException("Source entity ${sourceEntityId} does not exist")
            }
            new SourceEntityKey(
                result.getString('source_budget_id'),
                SourceEntityType.valueOf(result.getString('entity_type').toUpperCase()),
                result.getString('parent_transaction_id'), result.getString('parent_subtransaction_id'),
                result.getString('money_movement_id'))
        }
    }

    void recordOperationAttempt(long operationId, String outcome, String failureReason = null,
                                String returnedChildTransactionId = null) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO operation_attempts(sync_operation_id, attempted_at, outcome, failure_reason,
                                               returned_child_transaction_id)
                VALUES (?, ?, ?, ?, ?)
            ''')
            statement.setLong(1, operationId)
            statement.setString(2, now())
            statement.setString(3, outcome)
            statement.setString(4, failureReason)
            statement.setString(5, returnedChildTransactionId)
            statement.executeUpdate()
        }
    }

    void markOperationApplied(long operationId) {
        updateOperationStatus(operationId, 'applied', now())
    }

    void markOperationRetryable(long operationId) {
        updateOperationStatus(operationId, 'retryable_failed', null)
    }

    void completeCreateOperation(long operationId, long sourceEntityId, Long oldMirrorId,
                                 String targetBudgetId, String direction, String childTransactionId,
                                 String targetAccountId, String payloadHash, boolean recreation) {
        withTransaction { Connection connection ->
            if (oldMirrorId != null && recreation) {
                def retire = connection.prepareStatement('''
                    UPDATE child_mirrors SET status = 'missing', ended_at = ?
                    WHERE id = ? AND status = 'active'
                ''')
                retire.setString(1, now())
                retire.setLong(2, oldMirrorId)
                retire.executeUpdate()
            }
            def existing = connection.prepareStatement('''
                SELECT id FROM child_mirrors
                WHERE source_entity_id = ? AND target_budget_id = ? AND direction = ?
                  AND child_transaction_id = ? AND status = 'active'
            ''')
            existing.setLong(1, sourceEntityId)
            existing.setString(2, targetBudgetId)
            existing.setString(3, direction)
            existing.setString(4, childTransactionId)
            if (!existing.executeQuery().next()) {
                def insert = connection.prepareStatement('''
                    INSERT INTO child_mirrors(source_entity_id, target_budget_id, direction,
                                              child_transaction_id, target_account_id,
                                              authoritative_payload_hash, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
                ''')
                insert.setLong(1, sourceEntityId)
                insert.setString(2, targetBudgetId)
                insert.setString(3, direction)
                insert.setString(4, childTransactionId)
                insert.setString(5, targetAccountId)
                insert.setString(6, payloadHash)
                insert.setString(7, now())
                insert.executeUpdate()
            }
            markOperationAppliedInTransaction(connection, operationId)
        }
    }

    void completeUpdateOperation(long operationId, long mirrorId, String targetAccountId, String payloadHash) {
        withTransaction { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE child_mirrors SET target_account_id = ?, authoritative_payload_hash = ?
                WHERE id = ? AND status = 'active'
            ''')
            statement.setString(1, targetAccountId)
            statement.setString(2, payloadHash)
            statement.setLong(3, mirrorId)
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Active child mirror ${mirrorId} does not exist")
            }
            markOperationAppliedInTransaction(connection, operationId)
        }
    }

    void completeDeleteOperation(long operationId, Long mirrorId) {
        withTransaction { Connection connection ->
            if (mirrorId != null) {
                def statement = connection.prepareStatement('''
                    UPDATE child_mirrors SET status = 'deleted', ended_at = ?
                    WHERE id = ? AND status = 'active'
                ''')
                statement.setString(1, now())
                statement.setLong(2, mirrorId)
                statement.executeUpdate()
            }
            markOperationAppliedInTransaction(connection, operationId)
        }
    }

    long recordMirrorCreated(long sourceEntityId, String targetBudgetId, String direction,
                             String childTransactionId, String targetAccountId = null,
                             String payloadHash = null) {
        transitionMirror(null, sourceEntityId, targetBudgetId, direction, childTransactionId,
            targetAccountId, payloadHash, null)
    }

    void recordMirrorUpdated(long mirrorId, String targetAccountId, String payloadHash) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE child_mirrors
                SET target_account_id = ?, authoritative_payload_hash = ?
                WHERE id = ? AND status = 'active'
            ''')
            statement.setString(1, targetAccountId)
            statement.setString(2, payloadHash)
            statement.setLong(3, mirrorId)
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Active child mirror ${mirrorId} does not exist")
            }
        }
    }

    void recordMirrorDeleted(long mirrorId) {
        retireMirror(mirrorId, 'deleted')
    }

    long recordMirrorReplacement(long oldMirrorId, String childTransactionId, String targetBudgetId,
                                 String direction, String targetAccountId = null, String payloadHash = null) {
        transitionMirror(oldMirrorId, null, targetBudgetId, direction, childTransactionId,
            targetAccountId, payloadHash, 'replaced')
    }

    long recordMirrorRecreation(long oldMirrorId, String childTransactionId, String targetBudgetId,
                                String direction, String targetAccountId = null, String payloadHash = null) {
        transitionMirror(oldMirrorId, null, targetBudgetId, direction, childTransactionId,
            targetAccountId, payloadHash, 'missing')
    }

    MigrationProjection projectLegacyMigration() {
        if (!Files.exists(Paths.get(databasePath))) {
            return new MigrationProjection([], [])
        }
        withConnection { Connection connection ->
            if (!tableExists(connection, 'applied_transactions')) {
                return new MigrationProjection([], [])
            }
            legacyProjection(connection)
        }
    }

    long startRun(boolean dryRun, int pollingIntervalSeconds, String sourceBudgetId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_runs(started_at, dry_run, status, polling_interval_seconds, source_budget_id)
                VALUES (?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setString(1, now())
            statement.setInt(2, dryRun ? 1 : 0)
            statement.setString(3, 'running')
            statement.setInt(4, pollingIntervalSeconds)
            statement.setString(5, sourceBudgetId)
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    void finishRun(long runId, String status, String errorSummary) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE sync_runs
                SET completed_at = ?, status = ?, error_summary = ?
                WHERE id = ?
            ''')
            statement.setString(1, now())
            statement.setString(2, status)
            statement.setString(3, errorSummary)
            statement.setLong(4, runId)
            statement.executeUpdate()
        }
    }

    long recordSourceEvent(ChildTransactionPlan plan) {
        String fingerprint = plan.idempotencyKey
        Long existingId = findSourceEventIdByFingerprint(fingerprint)
        if (existingId != null) {
            return existingId
        }
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO source_events(source_budget_id, event_type, parent_transaction_id, parent_subtransaction_id, money_movement_id, money_movement_group_id, event_date, ynab_server_knowledge, fingerprint, raw_summary_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setString(1, plan.sourceBudgetId)
            statement.setString(2, plan.eventType)
            statement.setString(3, plan.parentTransactionId)
            statement.setString(4, plan.parentSubtransactionId)
            statement.setString(5, plan.moneyMovementId)
            statement.setString(6, plan.moneyMovementGroupId)
            statement.setString(7, plan.date)
            statement.setObject(8, null)
            statement.setString(9, fingerprint)
            statement.setString(10, JsonOutput.toJson(plan.toSummaryMap()))
            statement.setString(11, now())
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    long recordMapping(long sourceEventId, ChildTransactionPlan plan, String targetBudgetId, String accountId) {
        Long existingId = findMappingIdByIdempotencyKey(plan.idempotencyKey)
        if (existingId != null) {
            return existingId
        }
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_mappings(source_event_id, target_budget_id, target_child_key, target_mapping_key, target_account_name, target_account_id, direction, planned_amount, planned_date, planned_payee_name, planned_memo, planned_category_id, idempotency_key, last_planned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setLong(1, sourceEventId)
            statement.setString(2, targetBudgetId)
            statement.setString(3, plan.targetChildKey)
            statement.setString(4, plan.mappingKey)
            statement.setString(5, plan.childAccountName)
            statement.setString(6, accountId)
            statement.setString(7, plan.amount >= 0 ? 'inflow' : 'outflow')
            statement.setInt(8, plan.amount)
            statement.setString(9, plan.date)
            statement.setString(10, plan.payeeName)
            statement.setString(11, plan.memo)
            statement.setObject(12, null)
            statement.setString(13, plan.idempotencyKey)
            statement.setString(14, now())
            statement.executeUpdate()
            def keys = statement.generatedKeys
            keys.next()
            keys.getLong(1)
        }
    }

    void recordAppliedTransaction(long mappingId, long runId, String targetBudgetId, String createdChildTransactionId, String status, String failureReason, boolean dryRun) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO applied_transactions(sync_mapping_id, sync_run_id, target_budget_id, created_child_transaction_id, applied_at, status, failure_reason, dry_run)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ''')
            statement.setLong(1, mappingId)
            statement.setLong(2, runId)
            statement.setString(3, targetBudgetId)
            statement.setString(4, createdChildTransactionId)
            statement.setString(5, now())
            statement.setString(6, status)
            statement.setString(7, failureReason)
            statement.setInt(8, dryRun ? 1 : 0)
            statement.executeUpdate()
        }
    }

    boolean hasAppliedIdempotencyKey(String idempotencyKey) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                SELECT 1
                FROM sync_mappings mapping
                JOIN applied_transactions applied ON applied.sync_mapping_id = mapping.id
                WHERE mapping.idempotency_key = ?
                  AND applied.status = 'applied'
                LIMIT 1
            ''')
            statement.setString(1, idempotencyKey)
            statement.executeQuery().next()
        }
    }

    Integer getCursor(String key) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT value_integer FROM sync_cursors WHERE key = ?')
            statement.setString(1, key)
            def rs = statement.executeQuery()
            if (rs.next()) {
                return rs.getInt(1)
            }
            null
        }
    }

    void setCursor(String key, Integer value) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_cursors(key, value_integer, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value_integer = excluded.value_integer, updated_at = excluded.updated_at
            ''')
            statement.setString(1, key)
            statement.setInt(2, value)
            statement.setString(3, now())
            statement.executeUpdate()
        }
    }

    private Long findSourceEventIdByFingerprint(String fingerprint) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT id FROM source_events WHERE fingerprint = ?')
            statement.setString(1, fingerprint)
            def rs = statement.executeQuery()
            rs.next() ? rs.getLong(1) : null
        }
    }

    private Long findMappingIdByIdempotencyKey(String idempotencyKey) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('SELECT id FROM sync_mappings WHERE idempotency_key = ?')
            statement.setString(1, idempotencyKey)
            def rs = statement.executeQuery()
            rs.next() ? rs.getLong(1) : null
        }
    }

    private void updateOperationStatus(long operationId, String status, String completedAt) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE sync_operations SET status = ?, completed_at = ?
                WHERE id = ? AND status != 'applied'
            ''')
            statement.setString(1, status)
            statement.setString(2, completedAt)
            statement.setLong(3, operationId)
            statement.executeUpdate()
        }
    }

    private static void markOperationAppliedInTransaction(Connection connection, long operationId) {
        def statement = connection.prepareStatement('''
            UPDATE sync_operations SET status = 'applied', completed_at = ?
            WHERE id = ? AND status != 'applied'
        ''')
        statement.setString(1, now())
        statement.setLong(2, operationId)
        if (statement.executeUpdate() != 1) {
            throw new IllegalStateException("Pending reconciliation operation ${operationId} does not exist")
        }
    }

    private Object withTransaction(Closure work) {
        withConnection { Connection connection ->
            connection.autoCommit = false
            try {
                Object result = work(connection)
                connection.commit()
                result
            } catch (Throwable failure) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private void retireMirror(long mirrorId, String status) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                UPDATE child_mirrors SET status = ?, ended_at = ? WHERE id = ? AND status = 'active'
            ''')
            statement.setString(1, status)
            statement.setString(2, now())
            statement.setLong(3, mirrorId)
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Active child mirror ${mirrorId} does not exist")
            }
        }
    }

    private long transitionMirror(Long oldMirrorId, Long sourceEntityId, String targetBudgetId,
                                  String direction, String childTransactionId, String targetAccountId,
                                  String payloadHash, String oldStatus) {
        withConnection { Connection connection ->
            connection.autoCommit = false
            try {
                Long resolvedSourceEntityId = sourceEntityId
                if (oldMirrorId != null) {
                    resolvedSourceEntityId = selectLong(connection,
                        "SELECT source_entity_id FROM child_mirrors WHERE id = ? AND status = 'active'", oldMirrorId)
                    def retire = connection.prepareStatement('''
                        UPDATE child_mirrors SET status = ?, ended_at = ? WHERE id = ? AND status = 'active'
                    ''')
                    retire.setString(1, oldStatus)
                    retire.setString(2, now())
                    retire.setLong(3, oldMirrorId)
                    if (retire.executeUpdate() != 1) {
                        throw new IllegalStateException("Active child mirror ${oldMirrorId} does not exist")
                    }
                }
                def insert = connection.prepareStatement('''
                    INSERT INTO child_mirrors(source_entity_id, target_budget_id, direction,
                                              child_transaction_id, target_account_id,
                                              authoritative_payload_hash, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
                ''', Statement.RETURN_GENERATED_KEYS)
                insert.setLong(1, resolvedSourceEntityId)
                insert.setString(2, targetBudgetId)
                insert.setString(3, direction)
                insert.setString(4, childTransactionId)
                insert.setString(5, targetAccountId)
                insert.setString(6, payloadHash)
                insert.setString(7, now())
                insert.executeUpdate()
                def keys = insert.generatedKeys
                keys.next()
                long id = keys.getLong(1)
                connection.commit()
                id
            } catch (Throwable failure) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private static int schemaVersion(Connection connection) {
        if (!tableExists(connection, 'schema_versions')) {
            return 0
        }
        def result = connection.createStatement().executeQuery('SELECT COALESCE(MAX(version), 0) FROM schema_versions')
        result.next()
        result.getInt(1)
    }

    private static void recordSchemaVersion(Connection connection, int version) {
        def statement = connection.prepareStatement('INSERT INTO schema_versions(version, applied_at) VALUES (?, ?)')
        statement.setInt(1, version)
        statement.setString(2, now())
        statement.executeUpdate()
    }

    private static boolean tableExists(Connection connection, String name) {
        def statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")
        statement.setString(1, name)
        statement.executeQuery().next()
    }

    private static void validateSourceKey(SourceEntityKey key) {
        boolean valid = key?.sourceBudgetId && (
            (key.type == SourceEntityType.TRANSACTION && key.parentTransactionId && !key.parentSubtransactionId && !key.moneyMovementId) ||
            (key.type == SourceEntityType.SUBTRANSACTION && key.parentTransactionId && key.parentSubtransactionId && !key.moneyMovementId) ||
            (key.type == SourceEntityType.MONEY_MOVEMENT && !key.parentTransactionId && !key.parentSubtransactionId && key.moneyMovementId)
        )
        if (!valid) {
            throw new IllegalArgumentException('Source entity identity does not match its type')
        }
    }

    private static String sourceIdentity(SourceEntityKey key) {
        [key.sourceBudgetId, key.type.databaseValue, key.parentTransactionId ?: '',
         key.parentSubtransactionId ?: '', key.moneyMovementId ?: '']
            .collect { String value -> "${value.length()}:${value}" }
            .join('|')
    }

    private static long selectLong(Connection connection, String sql, Object... parameters) {
        def statement = connection.prepareStatement(sql)
        parameters.eachWithIndex { Object parameter, int index -> statement.setObject(index + 1, parameter) }
        def result = statement.executeQuery()
        if (!result.next()) {
            throw new IllegalStateException('Expected state row does not exist')
        }
        result.getLong(1)
    }

    private static void bindOperation(def statement, ReconciliationOperationIntent intent) {
        statement.setString(1, intent.operationKey)
        statement.setObject(2, intent.ingestionBatchId)
        statement.setLong(3, intent.sourceEntityId)
        statement.setObject(4, intent.childMirrorId)
        statement.setInt(5, intent.operationSequence)
        statement.setString(6, intent.operationType.databaseValue)
        statement.setString(7, intent.targetBudgetId)
        statement.setString(8, intent.childTransactionId)
        statement.setString(9, intent.payloadJson)
        statement.setString(10, intent.payloadHash)
        statement.setObject(11, intent.dependsOnOperationId)
    }

    private static ReconciliationOperationIntent operationIntent(Connection connection, long operationId) {
        def statement = connection.prepareStatement('SELECT * FROM sync_operations WHERE id = ?')
        statement.setLong(1, operationId)
        def result = statement.executeQuery()
        if (!result.next()) {
            throw new IllegalStateException("Operation ${operationId} does not exist")
        }
        new ReconciliationOperationIntent(
            result.getString('operation_key'), nullableLong(result, 'ingestion_batch_id'),
            result.getLong('source_entity_id'), nullableLong(result, 'child_mirror_id'),
            result.getInt('operation_sequence'),
            ReconciliationOperationType.valueOf(result.getString('operation_type').toUpperCase()),
            result.getString('target_budget_id'), result.getString('child_transaction_id'),
            result.getString('payload_json'), result.getString('payload_hash'),
            nullableLong(result, 'depends_on_operation_id'))
    }

    private static List<ChildMirrorState> childMirrors(ResultSet result) {
        List<ChildMirrorState> mirrors = []
        while (result.next()) {
            mirrors << new ChildMirrorState(
                result.getLong('id'), result.getLong('source_entity_id'), result.getString('target_budget_id'),
                result.getString('direction'), result.getString('child_transaction_id'),
                result.getString('target_account_id'), result.getString('authoritative_payload_hash'),
                result.getString('status'), result.getString('created_at'), result.getString('ended_at'))
        }
        mirrors
    }

    private static List<ReconciliationOperation> operations(ResultSet result) {
        List<ReconciliationOperation> found = []
        while (result.next()) {
            Long batchId = nullableLong(result, 'ingestion_batch_id')
            Long mirrorId = nullableLong(result, 'child_mirror_id')
            Long dependencyId = nullableLong(result, 'depends_on_operation_id')
            def intent = new ReconciliationOperationIntent(
                result.getString('operation_key'), batchId, result.getLong('source_entity_id'), mirrorId,
                result.getInt('operation_sequence'),
                ReconciliationOperationType.valueOf(result.getString('operation_type').toUpperCase()),
                result.getString('target_budget_id'), result.getString('child_transaction_id'),
                result.getString('payload_json'), result.getString('payload_hash'), dependencyId)
            found << new ReconciliationOperation(result.getLong('id'), intent, result.getString('status'),
                result.getString('created_at'), result.getString('completed_at'))
        }
        found
    }

    private static Long nullableLong(ResultSet result, String column) {
        long value = result.getLong(column)
        result.wasNull() ? null : value
    }

    private static void backfillLegacyState(Connection connection) {
        Map<String, List<Map<String, Object>>> groups = eligibleLegacyRows(connection).groupBy { legacyGroupKey(it) }
        groups.keySet().sort().each { String groupKey ->
            List<Map<String, Object>> rows = groups[groupKey]
            SourceEntityKey source = legacySourceKey(rows[0])
            long entityId = upsertMigratedEntity(connection, source)
            Set<String> seenChildIds = [] as LinkedHashSet
            int cleanupSequence = 0
            rows.each { Map<String, Object> row ->
                String childId = row.child_transaction_id as String
                if (!seenChildIds.add(childId)) {
                    return
                }
                boolean active = seenChildIds.size() == 1
                long mirrorId = insertMigratedMirror(connection, entityId, row, active)
                if (!active) {
                    String operationKey = legacyCleanupKey(source, row.target_budget_id as String,
                        row.direction as String, childId)
                    def statement = connection.prepareStatement('''
                        INSERT INTO sync_operations(operation_key, source_entity_id, child_mirror_id,
                                                    operation_sequence, operation_type, target_budget_id,
                                                    child_transaction_id, status, created_at)
                        VALUES (?, ?, ?, ?, 'delete', ?, ?, 'pending', ?)
                    ''')
                    statement.setString(1, operationKey)
                    statement.setLong(2, entityId)
                    statement.setLong(3, mirrorId)
                    statement.setInt(4, cleanupSequence++)
                    statement.setString(5, row.target_budget_id as String)
                    statement.setString(6, childId)
                    statement.setString(7, now())
                    statement.executeUpdate()
                }
            }
        }
    }

    private static MigrationProjection legacyProjection(Connection connection) {
        List<LegacyMirrorProjection> mirrors = []
        List<ReconciliationOperationIntent> cleanup = []
        Map<String, List<Map<String, Object>>> groups = eligibleLegacyRows(connection).groupBy { legacyGroupKey(it) }
        groups.keySet().sort().each { String groupKey ->
            List<Map<String, Object>> rows = groups[groupKey]
            SourceEntityKey source = legacySourceKey(rows[0])
            Set<String> seenChildIds = [] as LinkedHashSet
            int sequence = 0
            rows.each { Map<String, Object> row ->
                String childId = row.child_transaction_id as String
                if (!seenChildIds.add(childId)) {
                    return
                }
                boolean active = seenChildIds.size() == 1
                mirrors << new LegacyMirrorProjection(source, row.target_budget_id as String,
                    row.direction as String, childId, active)
                if (!active) {
                    cleanup << new ReconciliationOperationIntent(
                        legacyCleanupKey(source, row.target_budget_id as String, row.direction as String, childId),
                        null, 0L, null, sequence++, ReconciliationOperationType.DELETE,
                        row.target_budget_id as String, childId, null, null, null)
                }
            }
        }
        new MigrationProjection(mirrors, cleanup)
    }

    private static List<Map<String, Object>> eligibleLegacyRows(Connection connection) {
        def result = connection.createStatement().executeQuery('''
            SELECT applied.id AS applied_id, applied.applied_at,
                   applied.target_budget_id, applied.created_child_transaction_id AS child_transaction_id,
                   mapping.direction, mapping.target_account_id,
                   event.source_budget_id, event.event_type, event.parent_transaction_id,
                   event.parent_subtransaction_id, event.money_movement_id
            FROM applied_transactions applied
            JOIN sync_mappings mapping ON mapping.id = applied.sync_mapping_id
            JOIN source_events event ON event.id = mapping.source_event_id
            WHERE applied.status = 'applied' AND applied.dry_run = 0
              AND applied.created_child_transaction_id IS NOT NULL
              AND TRIM(applied.created_child_transaction_id) != ''
            ORDER BY applied.applied_at DESC, applied.id DESC
        ''')
        List<Map<String, Object>> rows = []
        while (result.next()) {
            rows << [
                applied_id: result.getLong('applied_id'), applied_at: result.getString('applied_at'),
                target_budget_id: result.getString('target_budget_id'),
                child_transaction_id: result.getString('child_transaction_id'),
                direction: result.getString('direction'), target_account_id: result.getString('target_account_id'),
                source_budget_id: result.getString('source_budget_id'), event_type: result.getString('event_type'),
                parent_transaction_id: result.getString('parent_transaction_id'),
                parent_subtransaction_id: result.getString('parent_subtransaction_id'),
                money_movement_id: result.getString('money_movement_id')
            ]
        }
        rows
    }

    private static SourceEntityKey legacySourceKey(Map<String, Object> row) {
        new SourceEntityKey(row.source_budget_id as String,
            SourceEntityType.valueOf((row.event_type as String).toUpperCase()),
            row.parent_transaction_id as String, row.parent_subtransaction_id as String,
            row.money_movement_id as String)
    }

    private static String legacyGroupKey(Map<String, Object> row) {
        SourceEntityKey source = legacySourceKey(row)
        String direction = source.type == SourceEntityType.MONEY_MOVEMENT ? row.direction as String : ''
        [sourceIdentity(source), row.target_budget_id as String, direction]
            .collect { String value -> "${value.length()}:${value}" }.join('|')
    }

    private static long upsertMigratedEntity(Connection connection, SourceEntityKey source) {
        String timestamp = now()
        def statement = connection.prepareStatement('''
            INSERT INTO source_entities(identity_key, source_budget_id, entity_type, parent_transaction_id,
                                        parent_subtransaction_id, money_movement_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(identity_key) DO NOTHING
        ''')
        statement.setString(1, sourceIdentity(source))
        statement.setString(2, source.sourceBudgetId)
        statement.setString(3, source.type.databaseValue)
        statement.setString(4, source.parentTransactionId)
        statement.setString(5, source.parentSubtransactionId)
        statement.setString(6, source.moneyMovementId)
        statement.setString(7, timestamp)
        statement.setString(8, timestamp)
        statement.executeUpdate()
        selectLong(connection, 'SELECT id FROM source_entities WHERE identity_key = ?', sourceIdentity(source))
    }

    private static long insertMigratedMirror(Connection connection, long entityId, Map<String, Object> row,
                                             boolean active) {
        def statement = connection.prepareStatement('''
            INSERT INTO child_mirrors(source_entity_id, target_budget_id, direction, child_transaction_id,
                                      target_account_id, status, created_at, ended_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ''', Statement.RETURN_GENERATED_KEYS)
        statement.setLong(1, entityId)
        statement.setString(2, row.target_budget_id as String)
        statement.setString(3, row.direction as String)
        statement.setString(4, row.child_transaction_id as String)
        statement.setString(5, row.target_account_id as String)
        statement.setString(6, active ? 'active' : 'superseded')
        statement.setString(7, row.applied_at as String ?: now())
        statement.setString(8, active ? null : now())
        statement.executeUpdate()
        def keys = statement.generatedKeys
        keys.next()
        keys.getLong(1)
    }

    private static String legacyCleanupKey(SourceEntityKey source, String targetBudgetId,
                                           String direction, String childTransactionId) {
        'legacy-delete|' + [sourceIdentity(source), targetBudgetId, direction, childTransactionId]
            .collect { String value -> "${value.length()}:${value}" }.join('|')
    }

    private static void ensureColumn(Connection connection, String tableName, String columnName, String definition) {
        def columns = connection.createStatement().executeQuery("PRAGMA table_info(${tableName})")
        boolean exists = false
        while (columns.next()) {
            if (columns.getString('name') == columnName) {
                exists = true
                break
            }
        }
        if (!exists) {
            connection.createStatement().execute("ALTER TABLE ${tableName} ADD COLUMN ${columnName} ${definition}")
        }
    }

    private <T> T withConnection(Closure<T> closure) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${databasePath}")
        try {
            connection.createStatement().execute('PRAGMA foreign_keys = ON')
            closure.call(connection)
        } finally {
            connection.close()
        }
    }

    private static String now() {
        OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}
