import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Specification
import ynabbankofdad.model.AccountSnapshot
import ynabbankofdad.model.CategorySnapshot
import ynabbankofdad.sync.CycleBalanceReporter
import ynabbankofdad.sync.reconcile.ParentCategoryAccountCache
import ynabbankofdad.sync.reconcile.PlannedAction
import ynabbankofdad.sync.reconcile.PlannedReconciliationIntent
import ynabbankofdad.sync.state.SourceEntityKey
import ynabbankofdad.sync.state.SourceEntityType

class CycleBalanceReporterSpec extends Specification {

    def "create update and delete milliunits roll up per child account"() {
        expect:
        CycleBalanceReporter.netMilliunits(intent(PlannedAction.CREATE, 'acct', -1200, null)) == -1200
        CycleBalanceReporter.netMilliunits(intent(PlannedAction.UPDATE, 'acct', -800, -500)) == -300
        CycleBalanceReporter.netMilliunits(intent(PlannedAction.DELETE, 'acct', null, 400)) == -400
        CycleBalanceReporter.netMilliunits(intent(PlannedAction.NO_OP, 'acct', -100, -100)) == 0

        and:
        new CycleBalanceReporter().netChangeByAccount([
            intent(PlannedAction.CREATE, 'acct', -1200, null),
            intent(PlannedAction.UPDATE, 'acct', -800, -500),
            intent(PlannedAction.DELETE, 'acct', null, 400)
        ]).values().sum() == -1900
    }

    def "dry-run projects current plus net and live uses actual balance"() {
        given:
        def cache = cache('Child One Spend Bank', 'child-one-account-id', 'Child One Checking')
        def parent = ['Child One Spend Bank': new CategorySnapshot('cat', 'Child One Spend Bank', 98800)]
        def accounts = ['child-one': ['Child One Checking':
            new AccountSnapshot('child-one-account-id', 'Child One Checking', current)]]
        def appender = attach()
        def reporter = new CycleBalanceReporter()

        when:
        reporter.report(1, dryRun, cache, parent, [intent(PlannedAction.CREATE, 'child-one-account-id', -1200, null)],
            accounts)

        then:
        appender.list*.formattedMessage.any { it == expected }

        cleanup:
        detach(appender)

        where:
        dryRun | current | expected
        true   | 100000  | 'Cycle 1 child child-one account Child One Checking: netChange=-$1.20 current=$100.00 projected=$98.80 parent=$98.80 diff=$0.00'
        false  | 98800   | 'Cycle 1 child child-one account Child One Checking: netChange=-$1.20 actual=$98.80 parent=$98.80 diff=$0.00'
    }

    def "multiple cached parent categories sum on the parent side"() {
        given:
        def cache = new ParentCategoryAccountCache()
        cache.record('cd-1', 'Child Two Gold CD 07/31/26', 'child-two', 'cd-acct', 'CD Account')
        cache.record('cd-2', 'Child Two Gold CD 08/31/26', 'child-two', 'cd-acct', 'CD Account')
        def parent = [
            'Child Two Gold CD 07/31/26': new CategorySnapshot('cd-1', 'Child Two Gold CD 07/31/26', 20000),
            'Child Two Gold CD 08/31/26': new CategorySnapshot('cd-2', 'Child Two Gold CD 08/31/26', 30000)
        ]
        def accounts = ['child-two': ['CD Account': new AccountSnapshot('cd-acct', 'CD Account', 50000)]]
        def appender = attach()

        when:
        new CycleBalanceReporter().report(1, false, cache, parent, [], accounts)

        then:
        appender.list*.formattedMessage.any {
            it == 'Cycle 1 child child-two account CD Account: netChange=$0.00 actual=$50.00 parent=$50.00 diff=$0.00'
        }

        cleanup:
        detach(appender)
    }

    def "reverse mapping uses the cached derived account name"() {
        given:
        def cache = cache('Child One Spend Bank', 'derived-id', 'Child One Spend')
        def parent = ['Child One Spend Bank': new CategorySnapshot('cat', 'Child One Spend Bank', 0)]
        def accounts = ['child-one': ['Child One Spend': new AccountSnapshot('derived-id', 'Child One Spend', 0)]]
        def appender = attach()

        when:
        new CycleBalanceReporter().report(1, true, cache, parent, [], accounts)

        then:
        appender.list*.formattedMessage.any { it.contains('account Child One Spend:') }
        !appender.list*.formattedMessage.any { it.contains('account Child One Checking:') }

        cleanup:
        detach(appender)
    }

    def "mismatch is a warning and not treated as a failure message"() {
        given:
        def cache = cache('Child One Spend Bank', 'child-one-account-id', 'Child One Checking')
        def parent = ['Child One Spend Bank': new CategorySnapshot('cat', 'Child One Spend Bank', 48000)]
        def accounts = ['child-one': ['Child One Checking':
            new AccountSnapshot('child-one-account-id', 'Child One Checking', 50000)]]
        def appender = attach()

        when:
        new CycleBalanceReporter().report(1, false, cache, parent, [], accounts)

        then:
        appender.list.any {
            it.level == Level.WARN &&
                it.formattedMessage == 'Cycle 1 balance mismatch child-one/Child One Checking: child=$50.00 parent=$48.00 diff=$2.00 (live actual)'
        }

        cleanup:
        detach(appender)
    }

    def "live missing account skips numeric compare"() {
        given:
        def cache = cache('Child One Spend Bank', 'missing-id', 'Child One Checking')
        def parent = ['Child One Spend Bank': new CategorySnapshot('cat', 'Child One Spend Bank', 1000)]
        def appender = attach()

        when:
        new CycleBalanceReporter().report(1, false, cache, parent, [], ['child-one': [:]])

        then:
        appender.list*.formattedMessage.any {
            it == 'Cycle 1 child child-one account Child One Checking: netChange=$0.00 actual=missing parent=$1.00 (skipped compare)'
        }
        appender.list.every { it.level != Level.WARN }

        cleanup:
        detach(appender)
    }

    def "unpropagated parent categories are not compared"() {
        given:
        def cache = cache('Child One Spend Bank', 'child-one-account-id', 'Child One Checking')
        def parent = [
            'Child One Spend Bank': new CategorySnapshot('cat-spend', 'Child One Spend Bank', 0),
            'Parent Only': new CategorySnapshot('cat-parent', 'Parent Only', 999)
        ]
        def appender = attach()

        when:
        new CycleBalanceReporter().report(1, true, cache, parent, [], [:])

        then:
        appender.list*.formattedMessage.any { it.contains('account Child One Checking:') }
        !appender.list*.formattedMessage.any { it.contains('Parent Only') }

        cleanup:
        detach(appender)
    }

    private static PlannedReconciliationIntent intent(PlannedAction action, String accountId,
                                                      Integer amount, Integer prior) {
        String payload = amount == null ? null :
            "{\"account_id\":\"${accountId}\",\"amount\":${amount}}"
        new PlannedReconciliationIntent('key', 1, action,
            new SourceEntityKey('parent', SourceEntityType.TRANSACTION, 'txn', null, null),
            'child-one', 'budget', 'outflow', 1L, 'child-txn', payload, 'hash', [], false,
            accountId, prior)
    }

    private static ParentCategoryAccountCache cache(String category, String accountId, String accountName) {
        def cache = new ParentCategoryAccountCache()
        cache.record('cat', category, 'child-one', accountId, accountName)
        cache
    }

    private static ListAppender<ILoggingEvent> attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(CycleBalanceReporter)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        appender
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(CycleBalanceReporter)).detachAppender(appender)
    }
}
