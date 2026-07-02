import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import ynabbankofdad.config.SyncLoggingConfig
import ynabbankofdad.sync.SyncLoggingBootstrap
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class SyncLoggingBootstrapSpec extends Specification {
    @TempDir
    Path tempDir

    def "configure writes actual sync log lines to configured file and applies level"() {
        given:
        Path logPath = tempDir.resolve('logs').resolve('sync.log')
        def config = new SyncLoggingConfig(logPath.toString(), 'DEBUG', 3, 1)

        when:
        SyncLoggingBootstrap.configure(config)
        LoggerFactory.getLogger('ynabbankofdad.sync.test').debug('sync logging bootstrap test line')

        then:
        logPath.toFile().exists()
        logPath.toFile().text.contains('sync logging bootstrap test line')

        and:
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory()
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level == Level.DEBUG
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(SyncLoggingBootstrap.SYNC_FILE_APPENDER_NAME) != null
    }
}
