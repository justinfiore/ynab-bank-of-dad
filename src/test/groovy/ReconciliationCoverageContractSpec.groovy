import spock.lang.Specification

class ReconciliationCoverageContractSpec extends Specification {
    static final File SPEC = new File('openspec/changes/reconcile-parent-transaction-changes/specs/parent-transaction-reconciliation/spec.md')
    static final File MATRIX = new File('openspec/changes/reconcile-parent-transaction-changes/test-coverage-matrix.md')

    def "matrix scenario set exactly equals normative specification"() {
        given:
        List<String> normative = SPEC.readLines().findAll { it.startsWith('#### Scenario: ') }
            .collect { it.substring('#### Scenario: '.size()) }
        List<Map<String, String>> rows = matrixRows()

        expect:
        normative.size() == 60
        rows*.scenario == normative
        rows*.scenario.unique().size() == normative.size()
        rows.every { it.unit && it.integration }
    }

    def "matrix references exact executable focused features"() {
        expect:
        matrixRows().every { row ->
            featureExists(row.unit, null) && featureExists(row.integration, true)
        }
    }

    static List<Map<String, String>> matrixRows() {
        MATRIX.readLines().findAll { String line ->
            line.startsWith('| ') && !line.startsWith('| Normative') && !line.startsWith('|---')
        }.collect { String line ->
            List<String> cells = line.split('\\|', -1).collect { it.trim() }.findAll { it }
            assert cells.size() == 3: "Malformed coverage matrix row: ${line}"
            [scenario: cells[0], unit: reference(cells[1]), integration: reference(cells[2])]
        }
    }

    static Map<String, String> reference(String cell) {
        String value = cell.replace('`', '')
        int separator = value.indexOf(': ')
        assert separator > 0: "Malformed test reference: ${cell}"
        [spec: value.substring(0, separator), feature: value.substring(separator + 2)]
    }

    static boolean featureExists(Map<String, String> reference, Boolean integration) {
        boolean namedIntegration = reference.spec.endsWith('IntegrationSpec') || reference.spec.endsWith('WireMockSpec')
        if (integration != null) {
            assert namedIntegration == integration:
                "${reference.spec} is assigned to the wrong Gradle test layer"
        }
        File source = new File("src/test/groovy/${reference.spec}.groovy")
        assert source.isFile(): "Missing referenced spec ${reference.spec}"
        String declaration = "def \"${reference.feature}\"()"
        assert source.text.contains(declaration):
            "Missing exact feature ${reference.spec}: ${reference.feature}"
        assert source.readLines().every { String line ->
            !line.trim().startsWith('@Ignore') && !line.trim().startsWith('@PendingFeature')
        }:
            "Referenced spec ${reference.spec} contains disabled features"
        true
    }
}
