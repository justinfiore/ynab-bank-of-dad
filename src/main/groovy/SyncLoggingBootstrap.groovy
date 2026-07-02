import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class SyncLoggingBootstrap {
    static void configure(SyncLoggingConfig config) {
        Path path = Paths.get(config.filePath)
        if (path.parent != null) {
            Files.createDirectories(path.parent)
        }
        if (!Files.exists(path)) {
            Files.createFile(path)
        }
        append(path, "[bootstrap] level=${config.level.toUpperCase()} maxHistory=${config.maxHistory} maxFileSizeMb=${config.maxFileSizeMb}\n")
    }

    private static void append(Path path, String text) {
        Files.writeString(path, text, StandardOpenOption.APPEND)
    }
}
