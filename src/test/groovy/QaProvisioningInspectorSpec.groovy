import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.qa.QaBudgetIdentity
import ynabbankofdad.qa.QaProvisioningDiscovery
import ynabbankofdad.qa.QaProvisioningInspector
import ynabbankofdad.qa.QaProvisioningRequirements

import java.nio.file.Path

class QaProvisioningInspectorSpec extends Specification {

    @TempDir
    Path tempDir

    def "emits deterministic missing provisioning evidence using only GET-only discovery"() {
        given:
        def parent = budget("Jorsten's Plan", 1)
        def jorstenJr = budget("Jorsten Jr's Plan", 2)
        def borsten = budget("Borsten's Plan", 3)
        def thorsten = budget("Thorsten's Plan", 4)
        def requirements = new QaProvisioningRequirements(
            parent,
            [jorstenJr, borsten, thorsten],
            ['QA Alpha', 'QA Beta'] as Set,
            [
                (jorstenJr): ['Silver', 'Bronze'] as Set,
                (borsten): ['Silver', 'Bronze'] as Set,
                (thorsten): ['Silver', 'Bronze'] as Set
            ]
        )
        def discovery = Mock(QaProvisioningDiscovery)
        def artifact = tempDir.resolve('nested/missing-provisioning.json')

        when:
        def result = new QaProvisioningInspector(discovery).inspect(requirements, artifact)

        then:
        1 * discovery.getParentCategoryNames(parent) >> (['QA Alpha'] as Set)
        1 * discovery.getChildAccountNames(jorstenJr) >> (['Silver', 'Bronze'] as Set)
        1 * discovery.getChildAccountNames(borsten) >> (['Silver'] as Set)
        1 * discovery.getChildAccountNames(thorsten) >> ([] as Set)
        0 * _
        result.complete == false
        artifact.toFile().isFile()

        and:
        def json = new JsonSlurper().parse(artifact.toFile())
        json.missingParentCategories == ['QA Beta']
        json.children*.budget*.displayName == ["Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan"]
        json.children*.missingAccounts == [[], ['Bronze'], ['Bronze', 'Silver']]
        json.complete == false
    }

    def "does not invoke a write-capable method or perform remote provisioning"() {
        given:
        def parent = budget("Jorsten's Plan", 1)
        def child = budget("Jorsten Jr's Plan", 2)
        def discovery = new WriteTrapDiscovery(
            parentCategories: ['QA Required'] as Set,
            childAccounts: ['Silver', 'Bronze'] as Set
        )
        def requirements = new QaProvisioningRequirements(
            parent,
            [child],
            ['QA Required'] as Set,
            [(child): ['Silver', 'Bronze'] as Set]
        )

        when:
        def result = new QaProvisioningInspector(discovery).inspect(
            requirements,
            tempDir.resolve('missing-provisioning.json')
        )

        then:
        result.complete
        discovery.parentReads == 1
        discovery.childReads == 1
        discovery.writeOperations == 0
    }

    private static QaBudgetIdentity budget(String name, int number) {
        new QaBudgetIdentity(name, String.format('20000000-0000-0000-0000-%012d', number))
    }

    private static class WriteTrapDiscovery implements QaProvisioningDiscovery {
        Set<String> parentCategories
        Set<String> childAccounts
        int parentReads
        int childReads
        int writeOperations

        @Override
        Set<String> getParentCategoryNames(QaBudgetIdentity ignored) {
            parentReads++
            parentCategories
        }

        @Override
        Set<String> getChildAccountNames(QaBudgetIdentity ignored) {
            childReads++
            childAccounts
        }

        void createCategory(String ignored) {
            writeOperations++
            throw new AssertionError('A provisioning write was attempted')
        }

        void createAccount(String ignored) {
            writeOperations++
            throw new AssertionError('A provisioning write was attempted')
        }
    }
}
