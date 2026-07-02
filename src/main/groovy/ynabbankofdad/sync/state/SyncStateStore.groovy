package ynabbankofdad.sync.state

import groovy.json.JsonOutput
import ynabbankofdad.sync.model.ChildTransactionPlan

import groovy.json.JsonOutput

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
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

class SyncStateStore implements SyncStateRepository {
    final String databasePath

    SyncStateStore(String databasePath) {
        this.databasePath = databasePath
    }

    void initialize() {
        Path dbPath = Paths.get(databasePath)
        if (dbPath.parent != null) {
            Files.createDirectories(dbPath.parent)
        }
        withConnection { Connection connection ->
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
                INSERT INTO sync_mappings(source_event_id, target_budget_id, target_child_key, target_account_id, direction, planned_amount, planned_date, planned_payee_name, planned_memo, planned_category_id, idempotency_key, last_planned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', java.sql.Statement.RETURN_GENERATED_KEYS)
            statement.setLong(1, sourceEventId)
            statement.setString(2, targetBudgetId)
            statement.setString(3, plan.targetChildKey)
            statement.setString(4, accountId)
            statement.setString(5, plan.amount >= 0 ? 'inflow' : 'outflow')
            statement.setInt(6, plan.amount)
            statement.setString(7, plan.date)
            statement.setString(8, plan.payeeName)
            statement.setString(9, plan.memo)
            statement.setObject(10, null)
            statement.setString(11, plan.idempotencyKey)
            statement.setString(12, now())
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
