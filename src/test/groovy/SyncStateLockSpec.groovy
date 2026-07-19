import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.state.SyncStateLock

import java.nio.file.Path

class SyncStateLockSpec extends Specification {
    @TempDir
    Path tempDir

    def "lock path is beside the database file"() {
        expect:
        SyncStateLock.lockPathFor('/tmp/state/sync.db').toString() == '/tmp/state/sync.db.lock'
    }

    def "second acquire against the same database aborts with a clear message"() {
        given:
        String dbPath = tempDir.resolve('sync.db').toString()

        when:
        SyncStateLock first = SyncStateLock.acquire(dbPath)
        IllegalStateException failure = null
        try {
            SyncStateLock.acquire(dbPath)
        } catch (IllegalStateException ex) {
            failure = ex
        } finally {
            first.close()
        }

        then:
        failure != null
        failure.message.contains('Another ParentChildBudgetSyncer already holds the live lock')
        failure.message.contains(dbPath)
        failure.message.contains(SyncStateLock.lockPathFor(dbPath).toString())
    }

    def "release allows a later process to acquire the same lock"() {
        given:
        String dbPath = tempDir.resolve('sync.db').toString()

        when:
        SyncStateLock first = SyncStateLock.acquire(dbPath)
        first.close()
        SyncStateLock second = SyncStateLock.acquire(dbPath)
        second.close()

        then:
        noExceptionThrown()
    }
}
