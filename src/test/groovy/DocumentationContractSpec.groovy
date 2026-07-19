import spock.lang.Specification

class DocumentationContractSpec extends Specification {
    def "README links the complete architecture and review guide"() {
        given:
        String readme = new File('README.md').text
        String architecture = new File('ARCHITECTURE.md').text

        expect:
        readme.contains('[ARCHITECTURE.md](ARCHITECTURE.md)')
        ['# Architecture', '## Record Allowance', '## Parent/Child Syncer',
         '## Parent Transaction Reconciliation', '### Code Review Checklist',
         '### Review Hotspots And Design Questions'].every { architecture.contains(it) }
        ['RecordAllowance', 'ParentChildBudgetSyncer', 'ParentTransactionReconciler',
         'ReconciliationOperationApplier', 'SyncStateStore'].every { architecture.contains(it) }
    }

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
        guide.contains('delete any database created by an earlier build')
        guide.contains('rejected without mutation')
    }

    def "human guide covers every normative reconciliation area represented by the matrix"() {
        given:
        String guide = new File('PARENT_TRANSACTION_RECONCILIATION.md').text.toLowerCase()

        expect:
        ['stable source', 'memo', 'unapproved', 'split', 'missing child', 'retry', 'cursor',
         'schema_versions', 'dry-run', 'money movement', 'delete', 'update'].every { guide.contains(it) }
    }
}
