import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.TempDir
import ynabbankofdad.qa.QaEvidenceConfig

import java.nio.file.Path

class QaEvidenceConfigSpec extends Specification {

    @TempDir
    Path tempDir

    def "QA example names every budget requirement without secrets or suffix IDs"() {
        given:
        def file = new File('qa/config/qa-sync.yaml.example')

        expect:
        file.isFile()

        when:
        Map config = new Yaml().load(file.text) as Map
        List<Map> budgets = [config.budgets.parent as Map] + (config.budgets.children as List<Map>)

        then:
        budgets*.displayName == [
            "Jorsten's Plan", "Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan"
        ]
        budgets*.fullId == [
            '${QA_PARENT_PLAN_ID}',
            '${QA_JORSTEN_JR_PLAN_ID}',
            '${QA_BORSTEN_PLAN_ID}',
            '${QA_THORSTEN_PLAN_ID}',
        ]
        !file.text.toLowerCase().contains('token')

        and:
        config.provisioning.parentCategories == [
            'QA Jorsten Jr Silver', 'QA Jorsten Jr Bronze',
            'QA Borsten Silver', 'QA Borsten Bronze',
            'QA Thorsten Silver', 'QA Thorsten Bronze',
            'QA Unmapped', 'QA Transfer Clearing'
        ]
        config.provisioning.requiredChildAccounts == ['Silver', 'Bronze']
        (config.mappingRequirements as List).count { it.mapped == true } == 6
        (config.mappingRequirements as List).count { it.mapped == false } == 2
        config.expectedMutationManifest[0].targetBudgetFullId ==
            'REPLACE_WITH_FULL_IMMUTABLE_PLAN_UUID'

        and:
        Map discoveryExample = new groovy.json.JsonSlurper().parse(
            new File('qa/config/discovered-provisioning.json.example')
        ) as Map
        ([discoveryExample.parentBudget] + discoveryExample.childBudgets).every {
            it.fullId == 'REPLACE_WITH_FULL_IMMUTABLE_PLAN_UUID'
        }
    }

    def "loads completed full IDs into exact provisioning requirements"() {
        given:
        def configFile = tempDir.resolve('qa-sync.yaml').toFile()
        configFile.text = '''
budgets:
  parent:
    displayName: "Jorsten's Plan"
    fullId: 30000000-0000-0000-0000-000000000001
  children:
    - displayName: "Jorsten Jr's Plan"
      fullId: 30000000-0000-0000-0000-000000000002
    - displayName: "Borsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000003
    - displayName: "Thorsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000004
provisioning:
  parentCategories:
    - QA Required
  requiredChildAccounts:
    - Silver
    - Bronze
'''

        when:
        def config = QaEvidenceConfig.load(configFile.toPath())

        then:
        config.provisioning.parentBudget.displayName == "Jorsten's Plan"
        config.provisioning.childBudgets*.displayName == [
            "Jorsten Jr's Plan", "Borsten's Plan", "Thorsten's Plan"
        ]
        config.provisioning.childAccountNames.values().every { it == ['Silver', 'Bronze'] as Set }
    }

    def "rejects a config that omits a required child budget"() {
        given:
        def configFile = tempDir.resolve('missing-child.yaml').toFile()
        configFile.text = '''
budgets:
  parent:
    displayName: "Jorsten's Plan"
    fullId: 30000000-0000-0000-0000-000000000001
  children:
    - displayName: "Jorsten Jr's Plan"
      fullId: 30000000-0000-0000-0000-000000000002
    - displayName: "Borsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000003
provisioning:
  parentCategories:
    - QA Required
  requiredChildAccounts:
    - Silver
'''

        when:
        QaEvidenceConfig.load(configFile.toPath())

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('child budgets must be exactly')
    }

    def "rejects a known child budget in the parent role"() {
        given:
        def configFile = tempDir.resolve('swapped-parent.yaml').toFile()
        configFile.text = '''
budgets:
  parent:
    displayName: "Jorsten Jr's Plan"
    fullId: 30000000-0000-0000-0000-000000000002
  children:
    - displayName: "Jorsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000001
    - displayName: "Borsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000003
    - displayName: "Thorsten's Plan"
      fullId: 30000000-0000-0000-0000-000000000004
provisioning:
  parentCategories:
    - QA Required
  requiredChildAccounts:
    - Silver
'''

        when:
        QaEvidenceConfig.load(configFile.toPath())

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains("parent budget must be exactly 'Jorsten's Plan'")
    }

    def "QA README gives an offline read-only invocation and disclaims live writes"() {
        given:
        def text = new File('qa/README.md').text

        expect:
        text.contains('./gradlew qaInspectProvisioning --offline --args=')
        text.contains('missing-provisioning.json')
        text.contains('does not read or manage access tokens')
        text.contains('does not perform live writes')
    }
}
