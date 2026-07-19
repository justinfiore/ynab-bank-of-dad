import spock.lang.Specification

class ReconciliationDocumentationIntegrationSpec extends Specification {
    def "guide and onboarding present one destructive dry-run workflow"() {
        given:
        String combined = ['README.md', 'QUICK_START.md', 'PARENT_TRANSACTION_RECONCILIATION.md']
            .collect { new File(it).text }.join('\n')

        expect:
        combined.contains('--dry-run --max-cycles 1')
        combined.contains('Delete the old child transaction, then create its replacement')
        combined.contains('do not skip the single-cycle dry run')
    }
}
