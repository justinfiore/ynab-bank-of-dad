import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.qa.QaBudgetIdentity
import ynabbankofdad.qa.QaSnapshotProvisioningDiscovery

import java.nio.file.Path

class QaSnapshotProvisioningDiscoverySpec extends Specification {

    @TempDir
    Path tempDir

    def "returns names only when snapshot display names and full IDs match exactly"() {
        given:
        def snapshot = snapshotFile('40000000-0000-0000-0000-000000000002')
        def discovery = new QaSnapshotProvisioningDiscovery(snapshot)

        expect:
        discovery.getParentCategoryNames(
            new QaBudgetIdentity("Jorsten's Plan", '40000000-0000-0000-0000-000000000001')
        ) == ['QA One', 'QA Two'] as Set
        discovery.getChildAccountNames(
            new QaBudgetIdentity("Jorsten Jr's Plan", '40000000-0000-0000-0000-000000000002')
        ) == ['Silver'] as Set
    }

    def "rejects a snapshot with the right child display name and wrong full ID"() {
        given:
        def discovery = new QaSnapshotProvisioningDiscovery(
            snapshotFile('40000000-0000-0000-0000-000000000099')
        )

        when:
        discovery.getChildAccountNames(
            new QaBudgetIdentity("Jorsten Jr's Plan", '40000000-0000-0000-0000-000000000002')
        )

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('not an allowed QA budget')
    }

    def "rejects unsanitized discovery fields instead of retaining or ignoring them"() {
        given:
        def file = tempDir.resolve('unsanitized.json').toFile()
        file.text = '''
{
  "parentBudget": {
    "displayName": "Jorsten's Plan",
    "fullId": "40000000-0000-0000-0000-000000000001",
    "categoryNames": [],
    "credential": "must-not-be-accepted"
  },
  "childBudgets": []
}
'''

        when:
        new QaSnapshotProvisioningDiscovery(file.toPath())

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('unsupported fields: credential')
    }

    private Path snapshotFile(String childId) {
        def file = tempDir.resolve("snapshot-${childId}.json").toFile()
        file.text = """
{
  "parentBudget": {
    "displayName": "Jorsten's Plan",
    "fullId": "40000000-0000-0000-0000-000000000001",
    "categoryNames": ["QA One", "QA Two"]
  },
  "childBudgets": [
    {
      "displayName": "Jorsten Jr's Plan",
      "fullId": "${childId}",
      "accountNames": ["Silver"]
    }
  ]
}
"""
        file.toPath()
    }
}
