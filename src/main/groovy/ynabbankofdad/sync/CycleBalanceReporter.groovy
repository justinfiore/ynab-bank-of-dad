package ynabbankofdad.sync

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.reconcile.ParentCategoryAccountCache
import ynabbankofdad.sync.reconcile.ParentCategoryAccountMapping
import ynabbankofdad.sync.reconcile.PlannedAction
import ynabbankofdad.sync.reconcile.PlannedReconciliationIntent
import ynabbankofdad.ynab.YnabLogFormatter

@Slf4j
class CycleBalanceReporter {
    private static final JsonSlurper JSON = new JsonSlurper()

    void report(int cycleNumber, boolean dryRun, ParentCategoryAccountCache cache,
                Map<String, CategorySnapshot> parentCategoriesByName,
                List<PlannedReconciliationIntent> intents,
                Map<String, Map<String, AccountSnapshot>> childAccountsByChildKey) {
        Map<String, Integer> netByAccount = netChangeByAccount(intents)
        cache.mappings().groupBy { accountKey(it.childKey, it.accountId, it.accountName) }
            .sort { it.key }
            .each { String key, List<ParentCategoryAccountMapping> mappings ->
                ParentCategoryAccountMapping sample = mappings.first()
                int net = netByAccount.getOrDefault(key, 0)
                int parentSide = mappings.collect { mapping ->
                    (parentCategoriesByName[mapping.parentCategoryName]?.balance ?: 0) as int
                }.sum() as int
                AccountSnapshot snapshot = findAccount(
                    childAccountsByChildKey[sample.childKey] ?: [:], sample.accountId, sample.accountName)
                reportAccount(cycleNumber, dryRun, sample, net, parentSide, snapshot)
            }
    }

    Map<String, Integer> netChangeByAccount(List<PlannedReconciliationIntent> intents) {
        Map<String, Integer> net = [:]
        (intents ?: []).findAll {
            it.action == PlannedAction.CREATE || it.action == PlannedAction.UPDATE ||
                it.action == PlannedAction.DELETE
        }.each { PlannedReconciliationIntent intent ->
            if ((intent.action == PlannedAction.UPDATE || intent.action == PlannedAction.DELETE) &&
                intent.priorAmount == null) {
                log.warn('Cycle account net change is incomplete for {} {} {}; priorAmount missing',
                    intent.action, intent.targetChildKey, intent.targetAccountId ?: accountIdFromPayload(intent))
            }
            String accountId = intent.targetAccountId ?: accountIdFromPayload(intent)
            if (!intent.targetChildKey || !accountId) {
                return
            }
            String key = accountKey(intent.targetChildKey, accountId, null)
            net[key] = (net[key] ?: 0) + netMilliunits(intent)
        }
        net
    }

    private void reportAccount(int cycleNumber, boolean dryRun, ParentCategoryAccountMapping mapping,
                               int net, int parentSide, AccountSnapshot snapshot) {
        String netText = YnabLogFormatter.formatAmount(net)
        String parentText = YnabLogFormatter.formatAmount(parentSide)
        if (dryRun) {
            int current = snapshot?.balance ?: 0
            int projected = current + net
            int diff = projected - parentSide
            log.info(
                'Cycle {} child {} account {}: netChange={} current={} projected={} parent={} diff={}',
                cycleNumber, mapping.childKey, mapping.accountName, netText,
                YnabLogFormatter.formatAmount(current), YnabLogFormatter.formatAmount(projected),
                parentText, YnabLogFormatter.formatAmount(diff))
            logDifference(cycleNumber, mapping, projected, parentSide, diff, 'dry-run projected')
            return
        }
        if (snapshot == null) {
            log.info(
                'Cycle {} child {} account {}: netChange={} actual=missing parent={} (skipped compare)',
                cycleNumber, mapping.childKey, mapping.accountName, netText, parentText)
            return
        }
        int actual = snapshot.balance ?: 0
        int diff = actual - parentSide
        log.info(
            'Cycle {} child {} account {}: netChange={} actual={} parent={} diff={}',
            cycleNumber, mapping.childKey, mapping.accountName, netText,
            YnabLogFormatter.formatAmount(actual), parentText, YnabLogFormatter.formatAmount(diff))
        logDifference(cycleNumber, mapping, actual, parentSide, diff, 'live actual')
    }

    private static void logDifference(int cycleNumber, ParentCategoryAccountMapping mapping,
                                      int childSide, int parentSide, int diff, String mode) {
        if (diff == 0) {
            return
        }
        log.warn(
            'Cycle {} balance mismatch {}/{}: child={} parent={} diff={} ({})',
            cycleNumber, mapping.childKey, mapping.accountName,
            YnabLogFormatter.formatAmount(childSide), YnabLogFormatter.formatAmount(parentSide),
            YnabLogFormatter.formatAmount(diff), mode)
    }

    static int netMilliunits(PlannedReconciliationIntent intent) {
        int prior = intent.priorAmount ?: 0
        switch (intent.action) {
            case PlannedAction.CREATE:
                return payloadAmount(intent) ?: 0
            case PlannedAction.UPDATE:
                return (payloadAmount(intent) ?: 0) - prior
            case PlannedAction.DELETE:
                return -prior
            default:
                return 0
        }
    }

    private static Integer payloadAmount(PlannedReconciliationIntent intent) {
        Object parsed = parsePayload(intent)
        parsed instanceof Map && parsed.amount instanceof Number ? (parsed.amount as Number).intValue() : null
    }

    private static String accountIdFromPayload(PlannedReconciliationIntent intent) {
        Object parsed = parsePayload(intent)
        parsed instanceof Map ? parsed.account_id as String : null
    }

    private static Object parsePayload(PlannedReconciliationIntent intent) {
        if (!intent?.payloadJson) {
            return null
        }
        JSON.parseText(intent.payloadJson)
    }

    private static String accountKey(String childKey, String accountId, String accountName) {
        "${childKey}|${accountId ?: accountName}"
    }

    private static AccountSnapshot findAccount(Map<String, AccountSnapshot> accounts,
                                               String accountId, String accountName) {
        if (accountName && accounts[accountName]) {
            return accounts[accountName]
        }
        accounts.values().find { it.id == accountId }
    }
}
