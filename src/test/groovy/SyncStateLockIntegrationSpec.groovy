import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.sync.state.SyncStateLock

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SyncStateLockIntegrationSpec extends Specification {
    @TempDir
    Path tempDir

    def "closing the holder unblocks a waiting second acquire"() {
        given:
        String dbPath = tempDir.resolve('sync.db').toString()
        SyncStateLock first = SyncStateLock.acquire(dbPath)

        when:
        Exception concurrentFailure = null
        try {
            SyncStateLock.acquire(dbPath)
        } catch (IllegalStateException ex) {
            concurrentFailure = ex
        }
        first.close()
        SyncStateLock second = SyncStateLock.acquire(dbPath)
        Path lockPath = second.lockPath
        second.close()

        then:
        concurrentFailure != null
        concurrentFailure.message.contains('Another ParentChildBudgetSyncer already holds the live lock')
        Files.exists(lockPath)
        Files.notExists(Path.of(dbPath))
    }

    def "lock survives holder process exit simulation and is re-acquirable"() {
        given:
        String dbPath = tempDir.resolve('resume.db').toString()
        Path lockPath = SyncStateLock.lockPathFor(dbPath)

        when: 'a holder acquires and then fully closes as an exited process would release the OS lock'
        SyncStateLock holder = SyncStateLock.acquire(dbPath)
        assert Files.exists(lockPath)
        holder.close()

        and: 'a later live run acquires the same state path'
        SyncStateLock resumed = SyncStateLock.acquire(dbPath)
        resumed.close()

        then:
        Files.exists(lockPath)
        noExceptionThrown()
    }

    def "dry-run style missing parent directory is created only for the lock file path when acquiring"() {
        given:
        Path nestedDb = tempDir.resolve('nested').resolve('sync.db')

        when:
        SyncStateLock lock = SyncStateLock.acquire(nestedDb.toString())
        Path lockPath = lock.lockPath
        lock.close()

        then:
        Files.exists(lockPath)
        Files.notExists(nestedDb)
    }

    def "two sequential live lock lifetimes never overlap on the same database path"() {
        given:
        String dbPath = tempDir.resolve('cron.db').toString()
        List<String> events = []

        when:
        SyncStateLock first = SyncStateLock.acquire(dbPath)
        events << 'first-held'
        first.close()
        events << 'first-released'
        SyncStateLock second = SyncStateLock.acquire(dbPath)
        events << 'second-held'
        second.close()
        events << 'second-released'

        then:
        events == ['first-held', 'first-released', 'second-held', 'second-released']
        // Brief pause documents that tryLock remains non-blocking between sequential cron-like runs.
        TimeUnit.MILLISECONDS.sleep(5)
        SyncStateLock.acquire(dbPath).withCloseable { true }
    }
}
