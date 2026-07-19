package ynabbankofdad.sync.state

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
    long startRun(int pollingIntervalSeconds, String sourceBudgetId)
    void finishRun(long runId, String status, String errorSummary)
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
                              Integer serverKnowledge, long ingestionBatchId)
    long createIngestionBatch(String batchKey, String sourceKind, Integer serverKnowledge)
    boolean completeIngestionBatchIfReady(long batchId)
    List<ChildMirrorState> findMirrorsForParent(String sourceBudgetId, String parentTransactionId,
                                                 boolean includeInactive)
    List<ChildMirrorState> findMirrorsForSource(SourceEntityKey source, boolean includeInactive)
    List<SourceEntityKey> findSourceEntities(String sourceBudgetId, SourceEntityType type)
    void setSourceLifecycle(long sourceEntityId, String lifecycleStatus)
    long createOperation(ReconciliationOperationIntent intent)
}

class DryRunSyncStateRepository implements SyncStateRepository, ReconciliationSyncStateRepository {
    private final SyncStateStore existingState
    private final Path databasePath

    DryRunSyncStateRepository(SyncStateStore existingState, String databasePath) {
        this.existingState = existingState
        this.databasePath = Paths.get(databasePath)
    }

    void initialize() {}
    long startRun(int pollingIntervalSeconds, String sourceBudgetId) { -1L }
    void finishRun(long runId, String status, String errorSummary) {}
    Integer getCursor(String key) {
        reconciliationSchemaAvailable() ? existingState.getCursor(key) : null
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
    long upsertSourceEntity(SourceEntityKey key) { throw dryRunWrite() }
    long appendSourceRevision(long sourceEntityId, String revisionHash, String normalizedJson,
                              Integer serverKnowledge, long ingestionBatchId) { throw dryRunWrite() }
    long createIngestionBatch(String batchKey, String sourceKind, Integer serverKnowledge) { throw dryRunWrite() }
    boolean completeIngestionBatchIfReady(long batchId) { throw dryRunWrite() }
    void setSourceLifecycle(long sourceEntityId, String lifecycleStatus) { throw dryRunWrite() }
    long createOperation(ReconciliationOperationIntent intent) { throw dryRunWrite() }
    List<ReconciliationOperation> findReadyOperations(int limit) { [] }
    ReconciliationOperationAttempt findSuccessfulOperationAttempt(long operationId) { null }
    SourceEntityKey findSourceEntityKey(long sourceEntityId) {
        if (!reconciliationSchemaAvailable()) {
            throw new IllegalStateException("Source entity ${sourceEntityId} does not exist")
        }
        existingState.findSourceEntityKey(sourceEntityId)
    }
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
    static final int CURRENT_SCHEMA_VERSION = 1
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
                int version = validateSchemaVersion(connection)
                for (int nextVersion = version + 1; nextVersion <= CURRENT_SCHEMA_VERSION; nextVersion++) {
                    migration(nextVersion).call(connection)
                    migrationStepHook?.call(nextVersion, connection)
                    recordSchemaVersion(connection, nextVersion)
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
        withReadOnlyConnection { Connection connection ->
            validateSchemaVersion(connection) == CURRENT_SCHEMA_VERSION
        }
    }

    private static Closure migration(int version) {
        switch (version) {
            case 1: return SyncStateStore.&createBaselineSchema
            default: throw new IllegalStateException("No state migration is defined for version ${version}")
        }
    }

    private static void createBaselineSchema(Connection connection) {
        connection.createStatement().execute('''
            CREATE TABLE schema_versions (
                version INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE sync_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT NOT NULL,
                completed_at TEXT NULL,
                status TEXT NOT NULL CHECK(status IN ('running', 'succeeded', 'partial', 'failed')),
                error_summary TEXT NULL,
                polling_interval_seconds INTEGER NULL,
                source_budget_id TEXT NOT NULL
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE sync_cursors (
                key TEXT PRIMARY KEY,
                value_integer INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            )
        ''')
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
            CREATE TABLE ingestion_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_key TEXT NOT NULL UNIQUE,
                source_kind TEXT NOT NULL CHECK(source_kind IN ('transaction_delta', 'money_movement_snapshot')),
                ynab_server_knowledge INTEGER NULL,
                status TEXT NOT NULL CHECK(status IN ('pending', 'completed')),
                created_at TEXT NOT NULL,
                completed_at TEXT NULL
            )
        ''')
        connection.createStatement().execute('''
            CREATE TABLE source_revisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_entity_id INTEGER NOT NULL,
                revision_hash TEXT NOT NULL,
                normalized_json TEXT NOT NULL,
                ynab_server_knowledge INTEGER NULL,
                ingestion_batch_id INTEGER NOT NULL,
                observed_at TEXT NOT NULL,
                UNIQUE(source_entity_id, revision_hash),
                FOREIGN KEY(source_entity_id) REFERENCES source_entities(id),
                FOREIGN KEY(ingestion_batch_id) REFERENCES ingestion_batches(id)
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
                status TEXT NOT NULL CHECK(status IN ('active', 'deleted', 'replaced', 'missing')),
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
                ingestion_batch_id INTEGER NOT NULL,
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
            ON sync_operations(status, ingestion_batch_id, operation_sequence, id)
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
                              Integer serverKnowledge, long ingestionBatchId) {
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
                ORDER BY operation.ingestion_batch_id, operation.operation_sequence, operation.id
                LIMIT ?
            ''')
            statement.setInt(1, limit)
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

    long startRun(int pollingIntervalSeconds, String sourceBudgetId) {
        withConnection { Connection connection ->
            def statement = connection.prepareStatement('''
                INSERT INTO sync_runs(started_at, status, polling_interval_seconds, source_budget_id)
                VALUES (?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setString(1, now())
            statement.setString(2, 'running')
            statement.setInt(3, pollingIntervalSeconds)
            statement.setString(4, sourceBudgetId)
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

    private static int validateSchemaVersion(Connection connection) {
        if (!tableExists(connection, 'schema_versions')) {
            if (hasUserTables(connection)) {
                throw unversionedDatabase()
            }
            return 0
        }

        def result = connection.createStatement().executeQuery('SELECT version FROM schema_versions ORDER BY version')
        List<Integer> versions = []
        while (result.next()) {
            versions << result.getInt(1)
        }
        if (versions.empty) {
            throw unversionedDatabase()
        }
        versions.eachWithIndex { int version, int index ->
            int expected = index + 1
            if (version != expected) {
                throw new IllegalStateException(
                    "State schema versions must be contiguous from 1; expected ${expected} but found ${version}")
            }
        }
        int version = versions.last()
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException(
                "State schema version ${version} is newer than supported version ${CURRENT_SCHEMA_VERSION}")
        }
        if (version == CURRENT_SCHEMA_VERSION) {
            validateCurrentSchema(connection)
        }
        version
    }

    private static void validateCurrentSchema(Connection connection) {
        Map<String, List<String>> expectedColumns = [
            schema_versions   : ['version', 'applied_at'],
            sync_runs         : ['id', 'started_at', 'completed_at', 'status', 'error_summary',
                                 'polling_interval_seconds', 'source_budget_id'],
            sync_cursors      : ['key', 'value_integer', 'updated_at'],
            source_entities   : ['id', 'identity_key', 'source_budget_id', 'entity_type',
                                 'parent_transaction_id', 'parent_subtransaction_id', 'money_movement_id',
                                 'lifecycle_status', 'created_at', 'updated_at'],
            ingestion_batches: ['id', 'batch_key', 'source_kind', 'ynab_server_knowledge', 'status',
                                 'created_at', 'completed_at'],
            source_revisions  : ['id', 'source_entity_id', 'revision_hash', 'normalized_json',
                                 'ynab_server_knowledge', 'ingestion_batch_id', 'observed_at'],
            child_mirrors     : ['id', 'source_entity_id', 'target_budget_id', 'direction',
                                 'child_transaction_id', 'target_account_id', 'authoritative_payload_hash',
                                 'status', 'created_at', 'ended_at'],
            sync_operations   : ['id', 'operation_key', 'ingestion_batch_id', 'source_entity_id',
                                 'child_mirror_id', 'operation_sequence', 'operation_type', 'target_budget_id',
                                 'child_transaction_id', 'payload_json', 'payload_hash',
                                 'depends_on_operation_id', 'status', 'created_at', 'completed_at'],
            operation_attempts: ['id', 'sync_operation_id', 'attempted_at', 'outcome', 'failure_reason',
                                 'returned_child_transaction_id']
        ]
        def result = connection.createStatement().executeQuery('''
            SELECT name FROM sqlite_master
            WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
        ''')
        Set<String> actual = [] as Set
        while (result.next()) {
            actual << result.getString(1)
        }
        if (actual != expectedColumns.keySet()) {
            throw new IllegalStateException(
                'Existing sync state database is not the supported fresh schema; delete it and run again')
        }
        expectedColumns.each { String table, List<String> expected ->
            def columns = connection.createStatement().executeQuery("PRAGMA table_info(${table})")
            List<String> actualColumns = []
            while (columns.next()) {
                actualColumns << columns.getString('name')
            }
            if (actualColumns != expected) {
                throw new IllegalStateException(
                    'Existing sync state database is not the supported fresh schema; delete it and run again')
            }
        }
        validateSchemaObjects(connection, 'index', ['one_active_child_mirror', 'ready_sync_operations'] as Set)
        validateSchemaObjects(connection, 'trigger', [
            'source_revisions_are_append_only', 'source_revisions_cannot_be_deleted',
            'operation_attempts_are_append_only', 'operation_attempts_cannot_be_deleted',
            'operation_intent_is_immutable', 'child_mirror_lineage_cannot_be_deleted',
            'child_mirror_identity_is_immutable'
        ] as Set)
    }

    private static void validateSchemaObjects(Connection connection, String type, Set<String> expected) {
        def result = connection.createStatement().executeQuery(
            "SELECT name FROM sqlite_master WHERE type = '${type}' AND sql IS NOT NULL")
        Set<String> actual = [] as Set
        while (result.next()) {
            actual << result.getString(1)
        }
        if (actual != expected) {
            throw new IllegalStateException(
                'Existing sync state database is not the supported fresh schema; delete it and run again')
        }
    }

    private static boolean hasUserTables(Connection connection) {
        connection.createStatement().executeQuery('''
            SELECT 1 FROM sqlite_master
            WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
            LIMIT 1
        ''').next()
    }

    private static IllegalStateException unversionedDatabase() {
        new IllegalStateException(
            'Existing sync state database is unversioned and unsupported; delete it and run again')
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
            result.getString('operation_key'), result.getLong('ingestion_batch_id'),
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
            long batchId = result.getLong('ingestion_batch_id')
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

    private <T> T withConnection(Closure<T> closure) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${databasePath}")
        try {
            connection.createStatement().execute('PRAGMA foreign_keys = ON')
            closure.call(connection)
        } finally {
            connection.close()
        }
    }

    private <T> T withReadOnlyConnection(Closure<T> closure) {
        String uriPath = Paths.get(databasePath).toAbsolutePath().toUri().rawPath
        Connection connection = DriverManager.getConnection("jdbc:sqlite:file:${uriPath}?mode=ro")
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
