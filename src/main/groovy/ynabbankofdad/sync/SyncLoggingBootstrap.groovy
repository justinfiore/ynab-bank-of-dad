package ynabbankofdad.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import org.slf4j.LoggerFactory
import ynabbankofdad.config.SyncLoggingConfig

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SyncLoggingBootstrap {
    static final String SYNC_FILE_APPENDER_NAME = 'SYNC_FILE'

    static void configure(SyncLoggingConfig config) {
        Path path = Paths.get(config.filePath)
        if (path.parent != null) {
            Files.createDirectories(path.parent)
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory()
        ch.qos.logback.classic.Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        rootLogger.detachAppender(SYNC_FILE_APPENDER_NAME)

        PatternLayoutEncoder encoder = new PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = '%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n'
        encoder.start()

        RollingFileAppender appender = new RollingFileAppender()
        appender.context = context
        appender.name = SYNC_FILE_APPENDER_NAME
        appender.file = config.filePath
        appender.encoder = encoder

        SizeAndTimeBasedRollingPolicy rollingPolicy = new SizeAndTimeBasedRollingPolicy()
        rollingPolicy.context = context
        rollingPolicy.parent = appender
        rollingPolicy.fileNamePattern = "${config.filePath}.%d{yyyy-MM-dd}.%i.gz"
        rollingPolicy.maxHistory = config.maxHistory
        rollingPolicy.maxFileSize = ch.qos.logback.core.util.FileSize.valueOf("${config.maxFileSizeMb}MB")
        rollingPolicy.start()

        appender.rollingPolicy = rollingPolicy
        appender.start()

        rootLogger.addAppender(appender)
        rootLogger.level = Level.toLevel(config.level.toUpperCase())
    }
}
