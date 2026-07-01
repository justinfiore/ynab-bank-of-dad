import spock.lang.Specification

class ScriptWrapperBehaviorSpec extends Specification {

    def "weekly bash wrapper runs normally after Java 25 gate"() {
        given:
        def script = new File('run-weekly-allowance.sh').text

        expect:
        script.contains('JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"')
        script.contains('Java 25 or later is required.')
        script.contains('Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.')
        script.contains('Detected java version: ${JAVA_VERSION_OUTPUT:-<unable to determine>}')
        script.contains('./gradlew --no-daemon run\n')
        !script.contains("--args='--dry-run'")
    }

    def "specific-date bash wrapper passes date without forcing dry-run"() {
        given:
        def script = new File('run-specific-allowance.sh').text

        expect:
        script.contains('JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"')
        script.contains('Java 25 or later is required.')
        script.contains('Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.')
        script.contains('Detected java version: ${JAVA_VERSION_OUTPUT:-<unable to determine>}')
        script.contains('./gradlew --no-daemon run --args="--date ${RUN_DATE} ${CONFIG_ARGS[*]:-}"')
        !script.contains('--dry-run')
    }

    def "weekly batch wrapper runs normally after Java 25 gate"() {
        given:
        def script = new File('RunWeeklyAllowance.bat').text

        expect:
        script.contains('java -version 2^>^&1')
        script.contains('Java 25 or later is required.')
        script.contains('Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.')
        script.contains('Detected java version:')
        script.contains('call gradlew.bat --no-daemon run || exit /b 1')
        !script.contains('--dry-run')
    }

    def "specific-date batch wrapper passes date without forcing dry-run"() {
        given:
        def script = new File('RunSpecificAllowance.bat').text

        expect:
        script.contains('java -version 2^>^&1')
        script.contains('Java 25 or later is required.')
        script.contains('Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.')
        script.contains('Detected java version:')
        script.contains('call gradlew.bat --no-daemon run --args="--date %RUN_DATE% %CONFIG_ARGS%" || exit /b 1')
        !script.contains('--dry-run')
    }
}
