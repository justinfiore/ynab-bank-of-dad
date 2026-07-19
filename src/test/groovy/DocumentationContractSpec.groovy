import spock.lang.Specification

class DocumentationContractSpec extends Specification {
    def "operator onboarding links the destructive reconciliation guide"() {
        given:
        String readme = new File('README.md').text
        String quickStart = new File('QUICK_START.md').text
        String guide = new File('PARENT_TRANSACTION_RECONCILIATION.md').text

        expect:
        readme.contains('[PARENT_TRANSACTION_RECONCILIATION.md](PARENT_TRANSACTION_RECONCILIATION.md)')
        quickStart.contains('[PARENT_TRANSACTION_RECONCILIATION.md](PARENT_TRANSACTION_RECONCILIATION.md)')
        guide.contains('Approval, deletion, and destructive behavior')
        guide.contains('review a `--dry-run --max-cycles 1`')
        guide.toLowerCase().contains('back up the configured sqlite')
    }

    def "human guide covers every normative reconciliation area represented by the matrix"() {
        given:
        String guide = new File('PARENT_TRANSACTION_RECONCILIATION.md').text.toLowerCase()

        expect:
        ['stable source', 'memo', 'unapproved', 'split', 'missing child', 'retry', 'cursor',
         'migration', 'dry-run', 'money movement', 'delete', 'update'].every { guide.contains(it) }
    }
}
