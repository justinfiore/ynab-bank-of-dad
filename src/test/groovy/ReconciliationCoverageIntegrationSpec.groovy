import spock.lang.Specification

class ReconciliationCoverageIntegrationSpec extends Specification {
    def "every matrix integration feature is discoverable and selected by testAll"() {
        given:
        String build = new File('build.gradle').text
        List<Map<String, String>> rows = ReconciliationCoverageContractSpec.matrixRows()

        expect:
        build.contains("'**/*WireMockSpec.class'")
        build.contains("'**/*IntegrationSpec.class'")
        build.contains('dependsOn(integrationTestTask)')
        rows.every { row ->
            ReconciliationCoverageContractSpec.featureExists(row.integration, true)
        }
        rows*.integration*.spec.unique().every {
            it.endsWith('WireMockSpec') || it.endsWith('IntegrationSpec')
        }
    }

    def "guide matrix and specification retain the reconciliation contract"() {
        given:
        String guide = new File('PARENT_TRANSACTION_RECONCILIATION.md').text
        Set<String> scenarios = ReconciliationCoverageContractSpec.matrixRows()*.scenario as Set
        Set<String> normative = ReconciliationCoverageContractSpec.SPEC.readLines()
            .findAll { it.startsWith('#### Scenario: ') }
            .collect { it.substring('#### Scenario: '.size()) } as Set

        expect:
        scenarios == normative
        ['parent-authoritative', 'child-owned', 'split transactions', 'money movements',
         'failure and retry behavior', 'dry-run guarantees'].every { guide.toLowerCase().contains(it) }
    }
}
