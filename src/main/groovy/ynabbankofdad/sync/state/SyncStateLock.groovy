package ynabbankofdad.sync.state

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Exclusive process lock for a live syncer against one SQLite state database.
 * Uses an OS file lock beside the database so kill -9 releases the lock automatically.
 */
class SyncStateLock implements AutoCloseable {
    final Path lockPath
    private final FileChannel channel
    private final FileLock lock

    private SyncStateLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath
        this.channel = channel
        this.lock = lock
    }

    static Path lockPathFor(String databasePath) {
        Paths.get(databasePath).toAbsolutePath().normalize().resolveSibling(
            Paths.get(databasePath).fileName.toString() + '.lock')
    }

    static SyncStateLock acquire(String databasePath) {
        Path lockPath = lockPathFor(databasePath)
        if (lockPath.parent != null) {
            Files.createDirectories(lockPath.parent)
        }
        FileChannel channel = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            FileLock lock
            try {
                lock = channel.tryLock()
            } catch (OverlappingFileLockException ignored) {
                lock = null
            }
            if (lock == null) {
                throw contention(databasePath, lockPath)
            }
            new SyncStateLock(lockPath, channel, lock)
        } catch (IllegalStateException failure) {
            closeQuietly(channel)
            throw failure
        } catch (Throwable failure) {
            closeQuietly(channel)
            throw new IllegalStateException(
                "Could not acquire live sync state lock for '${databasePath}' at '${lockPath}': ${failure.message}",
                failure)
        }
    }

    private static IllegalStateException contention(String databasePath, Path lockPath) {
        new IllegalStateException(
            "Another ParentChildBudgetSyncer already holds the live lock for sync state '${databasePath}' " +
                "(lock file '${lockPath}'). Aborting so two live processes do not share one state database. " +
                'If no other syncer is running, the previous process exited while still holding the lock; ' +
                'wait for the OS to release it or remove a stale lock only after confirming no process uses the file.')
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel?.close()
        } catch (Exception ignored) {
        }
    }

    @Override
    void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release()
            }
        } finally {
            channel?.close()
        }
    }
}
