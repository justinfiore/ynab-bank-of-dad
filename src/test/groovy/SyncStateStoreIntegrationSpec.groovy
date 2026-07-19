import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.state.SyncStateStore

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SyncStateStoreIntegrationSpec extends Specification {
    private static final Set<String> TABLES = [
        'schema_versions', 'sync_runs', 'sync_cursors', 'source_entities', 'ingestion_batches',
        'source_revisions', 'child_mirrors', 'sync_operations', 'operation_attempts'
    ] as Set

    @TempDir
    Path tempDir

    def "fresh initialization creates exactly the baseline tables and version"() {
        given:
        def store = store()

        when:
        store.initialize()

        then:
        userTables() == TABLES
        rows('SELECT version FROM schema_versions')*.version == [1]
        columns('sync_runs').containsAll(['id', 'started_at', 'completed_at', 'status',
                                          'error_summary', 'polling_interval_seconds', 'source_budget_id'])
        !columns('sync_runs').contains('dry_run')
        columns('sync_cursors') == ['key', 'value_integer', 'updated_at']
        !userTables().intersect(['source_events', 'sync_mappings', 'applied_transactions'])
    }

    def "initialization is repeatable"() {
        given:
        def store = store()

        when:
        store.initialize()
        store.initialize()

        then:
        userTables() == TABLES
        scalar('SELECT COUNT(*) FROM schema_versions') == 1
    }

    def "run and cursor state use the fresh columns"() {
        given:
        def store = initializedStore()

        when:
        long run = store.startRun(300, 'parent')
        store.finishRun(run, 'failed', 'boom')
        store.setCursor('transactions', 12)
        store.setCursor('transactions', 13)

        then:
        rows('SELECT status, error_summary, polling_interval_seconds, source_budget_id FROM sync_runs') == [[
            status: 'failed', error_summary: 'boom', polling_interval_seconds: 300, source_budget_id: 'parent'
        ]]
        store.getCursor('transactions') == 13
        rows('SELECT key, value_integer FROM sync_cursors') == [[key: 'transactions', value_integer: 13]]
    }

    def "nonempty unversioned database is rejected without mutation"() {
        given:
        execute('CREATE TABLE old_state(id INTEGER)')
        execute('INSERT INTO old_state VALUES (7)')
        byte[] before = Files.readAllBytes(database())

        when:
        store().initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('unversioned')
        failure.message.contains('delete it')
        Files.readAllBytes(database()) == before
        userTables() == ['old_state'] as Set
    }

    def "schema version gaps are rejected"() {
        given:
        execute('CREATE TABLE schema_versions(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)')
        execute("INSERT INTO schema_versions VALUES (1, 'now'), (3, 'now')")

        when:
        store().initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('contiguous')
        rows('SELECT version FROM schema_versions ORDER BY version')*.version == [1, 3]
    }

    def "newer contiguous schema is rejected"() {
        given:
        execute('CREATE TABLE schema_versions(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)')
        execute("INSERT INTO schema_versions VALUES (1, 'now'), (2, 'now')")

        when:
        store().initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('newer than supported')
        rows('SELECT version FROM schema_versions ORDER BY version')*.version == [1, 2]
    }

    def "versioned database with an unsupported shape is rejected without mutation"() {
        given:
        execute('CREATE TABLE schema_versions(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)')
        execute("INSERT INTO schema_versions VALUES (1, 'now')")
        ['sync_runs', 'sync_cursors', 'source_entities', 'ingestion_batches', 'source_revisions',
         'child_mirrors', 'sync_operations', 'operation_attempts'].each {
            execute("CREATE TABLE ${it}(id INTEGER)")
        }
        byte[] before = Files.readAllBytes(database())

        when:
        store().initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('not the supported fresh schema')
        Files.readAllBytes(database()) == before
    }

    def "baseline failure rolls all schema work back"() {
        given:
        def failing = new SyncStateStore(database().toString(), { int version, Connection ignored ->
            assert version == 1
            throw new IllegalStateException('injected migration failure')
        })

        when:
        failing.initialize()

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'injected migration failure'
        userTables().empty
    }

    private SyncStateStore store() {
        new SyncStateStore(database().toString())
    }

    private SyncStateStore initializedStore() {
        def value = store()
        value.initialize()
        value
    }

    private Path database() {
        tempDir.resolve('state.db')
    }

    private Set<String> userTables() {
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")*.name as Set
    }

    private List<String> columns(String table) {
        rows("PRAGMA table_info(${table})")*.name
    }

    private Object scalar(String sql) {
        rows(sql)[0].values().first()
    }

    private List<Map<String, Object>> rows(String sql) {
        withConnection { Connection connection ->
            def result = connection.createStatement().executeQuery(sql)
            List<Map<String, Object>> found = []
            while (result.next()) {
                Map<String, Object> row = [:]
                (1..result.metaData.columnCount).each { int index ->
                    row[result.metaData.getColumnLabel(index)] = result.getObject(index)
                }
                found << row
            }
            found
        }
    }

    private void execute(String sql) {
        withConnection { Connection connection -> connection.createStatement().execute(sql) }
    }

    private Object withConnection(Closure work) {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:${database()}")
        try {
            work(connection)
        } finally {
            connection.close()
        }
    }
}
