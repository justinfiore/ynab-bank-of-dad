import ynabbankofdad.sync.ChildSyncIdempotency
import ynabbankofdad.sync.model.ChildTransactionPlan
import spock.lang.Specification

class ChildSyncIdempotencySpec extends Specification {

    def 'buildKey substitutes stable target account id into planner provisional key'() {
        given:
        String provisional = ChildSyncIdempotency.composeKey(
            'parent-budget-id',
            'child-one',
            'spend',
            '',
            'transaction',
            'txn-1',
            null,
            null,
            '',
            'cat-spend',
            'Child One Spend Bank',
            -1200
        )
        def plan = new ChildTransactionPlan(
            'parent-budget-id', 'child-one', 'Child One Budget', 'spend', 'Child One Spend Bank',
            'transaction', 'txn-1', null, null, null, provisional, 'Child One Checking',
            '2026-07-01', -1200, 'Memo', null, true
        )

        when:
        String key = ChildSyncIdempotency.buildKey(plan, 'acct-spend-ynab-id')

        then:
        key.contains('|acct-spend-ynab-id|')
        !key.contains('|Child One Checking|')
        key.split('\\|')[3] == 'acct-spend-ynab-id'
    }

    def 'withKey preserves plan fields and only changes idempotency key'() {
        given:
        def plan = new ChildTransactionPlan(
            'p', 'c', 'Budget', 'm', 'Cat', 'transaction', 't', null, null, null, 'old-key', 'Acct',
            '2026-07-01', -100, 'memo', 'payee', true
        )

        when:
        def updated = ChildSyncIdempotency.withKey(plan, 'new-key')

        then:
        updated.idempotencyKey == 'new-key'
        updated.childAccountName == 'Acct'
        updated.amount == -100
    }
}