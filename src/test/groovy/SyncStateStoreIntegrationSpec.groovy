import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SyncStateStoreIntegrationSpec extends Specification {

    @TempDir
    Path tempDir

    def "initialize creates every SQLite table in a throwaway database"() {
        given:
        def store = new SyncStateStore(tempDir.resolve('syncstate.db').toString())

        when:
        store.initialize()

        then:
        tableNames(store.databasePath).containsAll([
            'sync_runs',
            'source_events',
            'sync_mappings',
            'applied_transactions',
            'sync_cursors'
        ])
    }

    def "run lifecycle persists started and finished metadata"() {
        given:
        def store = initializedStore()

        when:
        long runId = store.startRun(false, 300, 'parent-budget-id')
        store.finishRun(runId, 'failed', 'boom')

        then:
        def row = querySingleRow(store.databasePath, '''
            SELECT dry_run, status, error_summary, polling_interval_seconds, source_budget_id,
                   started_at, completed_at
            FROM sync_runs WHERE id = ?
        ''', runId)
        row.dry_run == 0
        row.status == 'failed'
        row.error_summary == 'boom'
        row.polling_interval_seconds == 300
        row.source_budget_id == 'parent-budget-id'
        row.started_at != null
        row.completed_at != null
    }

    def "recordSourceEvent stores transaction summaries and reuses existing fingerprint rows"() {
        given:
        def store = initializedStore()
        def plan = transactionPlan('txn-idem')

        when:
        long firstId = store.recordSourceEvent(plan)
        long secondId = store.recordSourceEvent(plan)

        then:
        firstId == secondId

        and:
        def rows = queryRows(store.databasePath, '''
            SELECT source_budget_id, event_type, parent_transaction_id, parent_subtransaction_id,
                   money_movement_id, money_movement_group_id, event_date, fingerprint,
                   raw_summary_json, created_at
            FROM source_events
        ''')
        rows.size() == 1
        rows[0].source_budget_id == 'parent-budget'
        rows[0].event_type == 'transaction'
        rows[0].parent_transaction_id == 'txn-1'
        rows[0].parent_subtransaction_id == null
        rows[0].money_movement_id == null
        rows[0].money_movement_group_id == null
        rows[0].event_date == '2026-07-01'
        rows[0].fingerprint == 'txn-idem'
        rows[0].created_at != null

        and:
        def summary = new JsonSlurper().parseText(rows[0].raw_summary_json as String) as Map
        summary.targetChildKey == 'child-one'
        summary.amount == -1200
        summary.memo == 'Shoes'
    }

    def "recordSourceEvent stores money movement identifiers and summaries"() {
        given:
        def store = initializedStore()
        def plan = new ChildTransactionPlan(
            'parent-budget', 'child-two', 'Child Two Budget', 'Child Two Spend Bank',
            'money_movement', null, null, 'mm-1', 'group-1', 'movement-idem', 'Child Two Checking',
            '2026-07-03', 500, 'Moved from Parent to Child Two Spend Bank', 'Moved from Parent to Child Two Spend Bank', true
        )

        when:
        long sourceEventId = store.recordSourceEvent(plan)

        then:
        sourceEventId > 0

        and:
        def row = querySingleRow(store.databasePath, '''
            SELECT event_type, parent_transaction_id, parent_subtransaction_id, money_movement_id,
                   money_movement_group_id, event_date, fingerprint
            FROM source_events WHERE id = ?
        ''', sourceEventId)
        row.event_type == 'money_movement'
        row.parent_transaction_id == null
        row.parent_subtransaction_id == null
        row.money_movement_id == 'mm-1'
        row.money_movement_group_id == 'group-1'
        row.event_date == '2026-07-03'
        row.fingerprint == 'movement-idem'
    }

    def "recordMapping stores target planning details and reuses existing idempotency keys"() {
        given:
        def store = initializedStore()
        def plan = transactionPlan('mapping-idem')
        long sourceEventId = store.recordSourceEvent(plan)

        when:
        long firstId = store.recordMapping(sourceEventId, plan, 'acct-1')
        long secondId = store.recordMapping(sourceEventId, plan, 'acct-2')

        then:
        firstId == secondId
        store.hasAppliedIdempotencyKey('mapping-idem')

        and:
        def row = querySingleRow(store.databasePath, '''
            SELECT source_event_id, target_budget_id, target_child_key, target_account_id,
                   direction, planned_amount, planned_date, planned_payee_name, planned_memo,
                   planned_category_id, idempotency_key, last_planned_at
            FROM sync_mappings WHERE id = ?
        ''', firstId)
        row.source_event_id == sourceEventId
        row.target_budget_id == 'Child One Budget'
        row.target_child_key == 'child-one'
        row.target_account_id == 'acct-1'
        row.direction == 'outflow'
        row.planned_amount == -1200
        row.planned_date == '2026-07-01'
        row.planned_payee_name == 'Payee'
        row.planned_memo == 'Shoes'
        row.planned_category_id == null
        row.idempotency_key == 'mapping-idem'
        row.last_planned_at != null
    }

    def "recordAppliedTransaction stores applied rows including dry-run flag and failure reason"() {
        given:
        def store = initializedStore()
        def plan = transactionPlan('applied-idem')
        long sourceEventId = store.recordSourceEvent(plan)
        long mappingId = store.recordMapping(sourceEventId, plan, 'acct-1')
        long runId = store.startRun(true, 120, 'parent-budget-id')

        when:
        store.recordAppliedTransaction(mappingId, runId, 'child-budget-id', 'child-txn-1', 'applied', null, true)
        store.recordAppliedTransaction(mappingId, runId, 'child-budget-id', null, 'failed', 'bad request', false)

        then:
        def rows = queryRows(store.databasePath, '''
            SELECT sync_mapping_id, sync_run_id, target_budget_id, created_child_transaction_id,
                   applied_at, status, failure_reason, dry_run
            FROM applied_transactions ORDER BY id
        ''')
        rows.size() == 2

        and:
        rows[0].sync_mapping_id == mappingId
        rows[0].sync_run_id == runId
        rows[0].target_budget_id == 'child-budget-id'
        rows[0].created_child_transaction_id == 'child-txn-1'
        rows[0].applied_at != null
        rows[0].status == 'applied'
        rows[0].failure_reason == null
        rows[0].dry_run == 1

        and:
        rows[1].created_child_transaction_id == null
        rows[1].status == 'failed'
        rows[1].failure_reason == 'bad request'
        rows[1].dry_run == 0
    }

    def "cursor reads null before set and upserts integer values"() {
        given:
        def store = initializedStore()

        expect:
        store.getCursor('transactions.last_server_knowledge') == null

        when:
        store.setCursor('transactions.last_server_knowledge', 77)
        def firstValue = store.getCursor('transactions.last_server_knowledge')
        store.setCursor('transactions.last_server_knowledge', 88)
        def secondValue = store.getCursor('transactions.last_server_knowledge')

        then:
        firstValue == 77
        secondValue == 88

        and:
        def rows = queryRows(store.databasePath, 'SELECT key, value_integer, updated_at FROM sync_cursors')
        rows.size() == 1
        rows[0].key == 'transactions.last_server_knowledge'
        rows[0].value_integer == 88
        rows[0].updated_at != null
    }

    private SyncStateStore initializedStore() {
        def store = new SyncStateStore(tempDir.resolve('syncstate.db').toString())
        store.initialize()
        store
    }

    private static ChildTransactionPlan transactionPlan(String idempotencyKey) {
        new ChildTransactionPlan(
            'parent-budget', 'child-one', 'Child One Budget', 'Child One Spend Bank',
            'transaction', 'txn-1', null, null, null, idempotencyKey, 'Child One Checking',
            '2026-07-01', -1200, 'Shoes', 'Payee', true
        )
    }

    private static List<String> tableNames(String dbPath) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            def statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")
            def rs = statement.executeQuery()
            def names = []
            while (rs.next()) {
                names << rs.getString(1)
            }
            names
        } finally {
            connection.close()
        }
    }

    private static Map<String, Object> querySingleRow(String dbPath, String sql, Object... params) {
        def rows = queryRows(dbPath, sql, params)
        assert rows.size() == 1
        rows[0]
    }

    private static List<Map<String, Object>> queryRows(String dbPath, String sql, Object... params) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            def statement = connection.prepareStatement(sql)
            params.eachWithIndex { param, index ->
                statement.setObject(index + 1, param)
            }
            def rs = statement.executeQuery()
            def meta = rs.metaData
            def rows = []
            while (rs.next()) {
                Map<String, Object> row = [:]
                (1..meta.columnCount).each { int columnIndex ->
                    row[meta.getColumnLabel(columnIndex)] = rs.getObject(columnIndex)
                }
                rows << row
            }
            rows
        } finally {
            connection.close()
        }
    }
}
