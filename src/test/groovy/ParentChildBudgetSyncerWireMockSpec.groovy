import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.DriverManager

import static com.github.tomakehurst.wiremock.client.WireMock.*

class ParentChildBudgetSyncerWireMockSpec extends Specification {

    @TempDir
    Path tempDir

    WireMockServer wireMockServer

    def setup() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
        configureFor('localhost', wireMockServer.port())
    }

    def cleanup() {
        wireMockServer.stop()
    }

    def "one live cycle mirrors approved parent transactions split transactions and money movements into child budgets"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unapproved', date: '2026-07-01', amount: -1300, memo: 'Ignore me', approved: false, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unmapped', date: '2026-07-01', amount: -1400, memo: 'Parent only', approved: true, category_id: 'cat-parent-only', category_name: 'Parent Only', subtransactions: []],
            [id: 'txn-split', date: '2026-07-02', amount: -1500, memo: 'Split parent memo', approved: true, category_id: null, category_name: null, subtransactions: [
                [id: 'sub-child-one-save', transaction_id: 'txn-split', amount: -700, memo: 'Split save', category_id: 'cat-child-one-save', category_name: 'Child One Save Bank'],
                [id: 'sub-child-two-spend', transaction_id: 'txn-split', amount: -800, memo: null, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank'],
                [id: 'sub-unmapped', transaction_id: 'txn-split', amount: -900, memo: 'Ignore split', category_id: 'cat-parent-only', category_name: 'Parent Only']
            ]]
        ], 41)
        stubMoneyMovements([
            [id: 'mm-transfer', money_movement_group_id: 'group-1', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-child-one-spend', to_category_id: 'cat-child-two-spend', amount: 500],
            [id: 'mm-parent-only', money_movement_group_id: 'group-2', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-parent-only', to_category_id: 'cat-parent-only', amount: 600]
        ])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-1', 'child-one-created-2', 'child-one-created-3'])
        stubChildPost('child-two-budget-id', ['child-two-created-1', 'child-two-created-2'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        List childOnePosts = postedTransactions('child-one-budget-id')
        childOnePosts.size() == 3
        childOnePosts*.account_id.unique() == ['child-one-account-id']
        childOnePosts.find { it.memo == 'YBOD: Shoes' && it.amount == -1200 && it.payee_name == null && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: Split save' && it.amount == -700 && it.payee_name == null && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: From Child One Spend Bank to Child Two Spend Bank' && it.amount == -500 && it.payee_name == 'To Child Two Spend Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        List childTwoPosts = postedTransactions('child-two-budget-id')
        childTwoPosts.size() == 2
        childTwoPosts*.account_id.unique() == ['child-two-account-id']
        childTwoPosts.find { it.memo == 'YBOD: Split parent memo' && it.amount == -800 && it.payee_name == null && it.category_id == null && it.cleared == 'cleared' }
        childTwoPosts.find { it.memo == 'YBOD: From Child One Spend Bank to Child Two Spend Bank' && it.amount == 500 && it.payee_name == 'From Child One Spend Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        allPostedImportIds().every { it.startsWith('PCBS:') }
        tableCount('sync_runs') == 1
        tableCount('source_events') == 5
        tableCount('sync_mappings') == 5
        tableCount('applied_transactions') == 5
        cursorValue('transactions.last_server_knowledge') == 41
    }

    def "four child budgets route literals regex split and money movements across multiple child accounts"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one-spend', date: '2026-07-01', amount: -1100, memo: 'Child one shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-one-bonus', date: '2026-07-01', amount: -1200, memo: 'Child one bonus', approved: true, category_id: 'cat-child-one-bonus', category_name: 'Child One Bonus Bank', subtransactions: []],
            [id: 'txn-child-two-cd', date: '2026-07-01', amount: -1300, memo: 'Child two CD', approved: true, category_id: 'cat-child-two-cd-0726', category_name: 'Child Two Gold CD 07/31/26', subtransactions: []],
            [id: 'txn-child-three-give', date: '2026-07-01', amount: -1400, memo: 'Child three give', approved: true, category_id: 'cat-child-three-give', category_name: 'Child Three Give Bank', subtransactions: []],
            [id: 'txn-child-four-bonus', date: '2026-07-01', amount: -1500, memo: 'Child four bonus', approved: true, category_id: 'cat-child-four-bonus', category_name: 'Child Four Bonus Bank', subtransactions: []],
            [id: 'txn-unapproved-four-child', date: '2026-07-01', amount: -9999, memo: 'Ignore unapproved', approved: false, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []],
            [id: 'txn-split-four-child', date: '2026-07-02', amount: -4500, memo: 'Four child split memo', approved: true, category_id: null, category_name: null, subtransactions: [
                [id: 'sub-child-one-save', transaction_id: 'txn-split-four-child', amount: -600, memo: 'Child one split save', category_id: 'cat-child-one-save', category_name: 'Child One Save Bank'],
                [id: 'sub-child-two-give', transaction_id: 'txn-split-four-child', amount: -700, memo: 'Child two split give', category_id: 'cat-child-two-give', category_name: 'Child Two Give Bank'],
                [id: 'sub-child-three-spend', transaction_id: 'txn-split-four-child', amount: -800, memo: null, category_id: 'cat-child-three-spend', category_name: 'Child Three Spend Bank'],
                [id: 'sub-child-four-cd', transaction_id: 'txn-split-four-child', amount: -900, memo: 'Child four split CD', category_id: 'cat-child-four-cd-0826', category_name: 'Child Four Gold CD 08/31/26'],
                [id: 'sub-four-unmapped', transaction_id: 'txn-split-four-child', amount: -1000, memo: 'Ignore unmapped split', category_id: 'cat-parent-only', category_name: 'Parent Only']
            ]]
        ], 101)
        stubMoneyMovements([
            [id: 'mm-one-to-two', money_movement_group_id: 'group-one-two', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-child-one-save', to_category_id: 'cat-child-two-spend', amount: 250],
            [id: 'mm-three-cd-to-four-give', money_movement_group_id: 'group-three-four', moved_at: '2026-07-04T12:00:00Z', from_category_id: 'cat-child-three-cd-0726', to_category_id: 'cat-child-four-give', amount: 350],
            [id: 'mm-parent-only-four-child', money_movement_group_id: 'group-parent-only', moved_at: '2026-07-05T12:00:00Z', from_category_id: 'cat-parent-only', to_category_id: 'cat-parent-only', amount: 450]
        ])
        stubChildAccounts('child-one-budget-id', [
            [id: 'child-one-spend-account-id', name: 'Child One Spend Account'],
            [id: 'child-one-save-account-id', name: 'Child One Save Account'],
            [id: 'child-one-cd-account-id', name: 'Child One CD Account']
        ])
        stubChildAccounts('child-two-budget-id', [
            [id: 'child-two-spend-account-id', name: 'Child Two Spend Account'],
            [id: 'child-two-give-account-id', name: 'Child Two Give Account'],
            [id: 'child-two-cd-account-id', name: 'Child Two CD Account']
        ])
        stubChildAccounts('child-three-budget-id', [
            [id: 'child-three-spend-account-id', name: 'Child Three Spend Account'],
            [id: 'child-three-give-account-id', name: 'Child Three Give Account'],
            [id: 'child-three-cd-account-id', name: 'Child Three CD Account']
        ])
        stubChildAccounts('child-four-budget-id', [
            [id: 'child-four-spend-account-id', name: 'Child Four Spend Account'],
            [id: 'child-four-give-account-id', name: 'Child Four Give Account'],
            [id: 'child-four-cd-account-id', name: 'Child Four CD Account']
        ])
        stubChildPost('child-one-budget-id', ['child-one-created-1', 'child-one-created-2', 'child-one-created-3', 'child-one-created-4'])
        stubChildPost('child-two-budget-id', ['child-two-created-1', 'child-two-created-2', 'child-two-created-3', 'child-two-created-4'])
        stubChildPost('child-three-budget-id', ['child-three-created-1', 'child-three-created-2', 'child-three-created-3'])
        stubChildPost('child-four-budget-id', ['child-four-created-1', 'child-four-created-2', 'child-four-created-3', 'child-four-created-4'])
        def syncer = syncer(false, fourChildSyncConfig())

        when:
        syncer.runOnce(1)

        then:
        List childOnePosts = postedTransactions('child-one-budget-id')
        childOnePosts.size() == 4
        childOnePosts.find { it.memo == 'YBOD: Child one shoes' && it.account_id == 'child-one-spend-account-id' && it.amount == -1100 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: Child one bonus' && it.account_id == 'child-one-save-account-id' && it.amount == -1200 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: Child one split save' && it.account_id == 'child-one-save-account-id' && it.amount == -600 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: From Child One Save Bank to Child Two Spend Bank' && it.account_id == 'child-one-save-account-id' && it.amount == -250 && it.payee_name == 'To Child Two Spend Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        List childTwoPosts = postedTransactions('child-two-budget-id')
        childTwoPosts.size() == 3
        childTwoPosts.find { it.memo == 'YBOD: Child two CD' && it.account_id == 'child-two-cd-account-id' && it.amount == -1300 && it.category_id == null && it.cleared == 'cleared' }
        childTwoPosts.find { it.memo == 'YBOD: Child two split give' && it.account_id == 'child-two-give-account-id' && it.amount == -700 && it.category_id == null && it.cleared == 'cleared' }
        childTwoPosts.find { it.memo == 'YBOD: From Child One Save Bank to Child Two Spend Bank' && it.account_id == 'child-two-spend-account-id' && it.amount == 250 && it.payee_name == 'From Child One Save Bank' && it.category_id == null && it.cleared == 'cleared' }
        !childTwoPosts.find { it.memo == 'Ignore unapproved' }

        and:
        List childThreePosts = postedTransactions('child-three-budget-id')
        childThreePosts.size() == 3
        childThreePosts.find { it.memo == 'YBOD: Child three give' && it.account_id == 'child-three-give-account-id' && it.amount == -1400 && it.category_id == null && it.cleared == 'cleared' }
        childThreePosts.find { it.memo == 'YBOD: Four child split memo' && it.account_id == 'child-three-spend-account-id' && it.amount == -800 && it.category_id == null && it.cleared == 'cleared' }
        childThreePosts.find { it.memo == 'YBOD: From Child Three Gold CD 07/31/26 to Child Four Give Bank' && it.account_id == 'child-three-cd-account-id' && it.amount == -350 && it.payee_name == 'To Child Four Give Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        List childFourPosts = postedTransactions('child-four-budget-id')
        childFourPosts.size() == 3
        childFourPosts.find { it.memo == 'YBOD: Child four bonus' && it.account_id == 'child-four-spend-account-id' && it.amount == -1500 && it.category_id == null && it.cleared == 'cleared' }
        childFourPosts.find { it.memo == 'YBOD: Child four split CD' && it.account_id == 'child-four-cd-account-id' && it.amount == -900 && it.category_id == null && it.cleared == 'cleared' }
        childFourPosts.find { it.memo == 'YBOD: From Child Three Gold CD 07/31/26 to Child Four Give Bank' && it.account_id == 'child-four-give-account-id' && it.amount == 350 && it.payee_name == 'From Child Three Gold CD 07/31/26' && it.category_id == null && it.cleared == 'cleared' }

        and:
        verify(2, getRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        verify(3, getRequestedFor(urlEqualTo('/v1/plans/child-two-budget-id/accounts')))
        verify(3, getRequestedFor(urlEqualTo('/v1/plans/child-three-budget-id/accounts')))
        verify(3, getRequestedFor(urlEqualTo('/v1/plans/child-four-budget-id/accounts')))
        tableCount('sync_runs') == 1
        tableCount('source_events') == 13
        tableCount('sync_mappings') == 13
        tableCount('applied_transactions') == 13
        mappingRows()*.target_child_key.toSet() == ['child-one', 'child-two', 'child-three', 'child-four'] as Set
        mappingRows().find { it.target_child_key == 'child-one' && it.target_mapping_key == 'save-shared' && it.target_account_name == 'Child One Save Account' }
        mappingRows().find { it.target_child_key == 'child-two' && it.target_mapping_key == 'cd-regex' && it.target_account_name == 'Child Two CD Account' }
        mappingRows().find { it.target_child_key == 'child-three' && it.target_mapping_key == 'cd-regex' && it.target_account_name == 'Child Three CD Account' }
        mappingRows().find { it.target_child_key == 'child-four' && it.target_mapping_key == 'spend-shared' && it.target_account_name == 'Child Four Spend Account' }
        cursorValue('transactions.last_server_knowledge') == 101
    }

    def "second live cycle uses saved transaction cursor and duplicate idempotency state prevents reposting"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 41)
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 42, 41)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 1
        tableCount('sync_runs') == 2
        tableCount('source_events') == 1
        tableCount('sync_mappings') == 1
        tableCount('applied_transactions') == 1
        cursorValue('transactions.last_server_knowledge') == 42
        verify(getRequestedFor(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .withQueryParam('last_knowledge_of_server', equalTo('41')))
    }

    def "dry run exercises WireMock reads and child account resolution without posting or mutating SQLite state"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 41)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        def syncer = syncer(true)

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk')))
        tableCount('sync_runs') == 0
        tableCount('source_events') == 0
        tableCount('sync_mappings') == 0
        tableCount('applied_transactions') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "child API failure records failed applied transaction state and continues other child targets"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 51)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPostFailure('child-one-budget-id')
        stubChildPost('child-two-budget-id', ['child-two-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows()*.status.sort() == ['applied', 'failed']
        appliedRows().find { it.status == 'failed' }.failure_reason.contains('YNAB POST /v1/plans/child-one-budget-id/transactions/bulk failed with status 500')
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "missing child account fails that child target with clear state while other targets still apply"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 52)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'wrong-account-id', 'Wrong Account')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-two-budget-id', ['child-two-created-1'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk')))
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows().find { it.status == 'failed' }.failure_reason.contains("Could not find account named 'Child One Checking'")
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "failed child transaction post is retried on a later run"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-retry', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 61)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostFailureThenSuccess('child-one-budget-id', ['child-one-created-after-retry'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 2
        appliedRows()*.status == ['failed', 'applied']
        cursorValue('transactions.last_server_knowledge') == 61
    }

    def "missing child account is retried after the account becomes available"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-account-retry', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 62)
        stubMoneyMovements([])
        stubChildAccountsFailureThenSuccess('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-after-account-retry'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 1
        appliedRows()*.status == ['failed', 'applied']
        cursorValue('transactions.last_server_knowledge') == 62
    }

    def "child auth failure records failed state isolates other child and does not advance cursor"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one-auth', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two-auth', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 71)
        stubMoneyMovements([])
        stubFor(get(urlEqualTo('/v1/plans/child-one-budget-id/accounts'))
            .willReturn(errorResponse(401, 'unauthorized child token')))
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-two-budget-id', ['child-two-created-auth'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows()*.status.sort() == ['applied', 'failed']
        appliedRows().find { it.status == 'failed' }.failure_reason.contains('YNAB GET /v1/plans/child-one-budget-id/accounts failed with status 401')
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "child plan discovery auth failure records failed state isolates other child and does not advance cursor"() {
        given:
        stubCommonBudgetDiscoveryFailureThenSuccess('child-one-budget-id', 403, 'forbidden child plan discovery')
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one-discovery-auth', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two-discovery-auth', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 72)
        stubMoneyMovements([])
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-two-budget-id', ['child-two-created-discovery-auth'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows()*.status.sort() == ['applied', 'failed']
        appliedRows().find { it.status == 'failed' }.failure_reason.contains('YNAB GET /v1/plans failed with status 403')
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "child transaction post auth failure records failed state isolates other child and does not advance cursor"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one-post-auth', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two-post-auth', date: '2026-07-01', amount: -2200, memo: 'Book', approved: true, category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 73)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPostFailure('child-one-budget-id', 403, 'forbidden child transaction post')
        stubChildPost('child-two-budget-id', ['child-two-created-post-auth'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        postedTransactions('child-two-budget-id').size() == 1
        appliedRows()*.status.sort() == ['applied', 'failed']
        appliedRows().find { it.status == 'failed' }.failure_reason.contains('YNAB POST /v1/plans/child-one-budget-id/transactions/bulk failed with status 403')
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "parent plan discovery failure stops before run state child reads or cursor updates"() {
        given:
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(errorResponse(503, 'parent plan list outage')))
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB GET /v1/plans failed with status 503')
        verify(0, getRequestedFor(urlMatching('/v1/plans/.*/accounts')))
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "parent category failure stops before child posts and cursor updates"() {
        given:
        stubCommonBudgetDiscovery()
        stubFor(get(urlEqualTo('/v1/plans/parent-budget-id/categories'))
            .willReturn(errorResponse(503, 'category outage')))
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB GET /v1/plans/parent-budget-id/categories failed with status 503')
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "parent transaction failure stops before child posts and cursor updates"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubFor(get(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .willReturn(errorResponse(500, 'transaction outage')))
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('/v1/plans/parent-budget-id/transactions')
        ex.message.contains('failed with status 500')
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "parent money movement failure stops before child posts and cursor updates"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([], 81)
        stubFor(get(urlEqualTo('/v1/plans/parent-budget-id/money_movements'))
            .willReturn(errorResponse(500, 'money movement outage')))
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB GET /v1/plans/parent-budget-id/money_movements failed with status 500')
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "no qualifying parent work records successful run without child resolution or mapping"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-unapproved', date: '2026-07-01', amount: -1300, memo: 'Ignore me', approved: false, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unmapped', date: '2026-07-01', amount: -1400, memo: 'Parent only', approved: true, category_id: 'cat-parent-only', category_name: 'Parent Only', subtransactions: []]
        ], 91)
        stubMoneyMovements([])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        verify(0, getRequestedFor(urlMatching('/v1/plans/child-.*-budget-id/accounts')))
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 1
        tableCount('source_events') == 0
        tableCount('sync_mappings') == 0
        tableCount('applied_transactions') == 0
        cursorValue('transactions.last_server_knowledge') == 91
    }

    def "single child money movement mirrors only the mapped side"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([], 92)
        stubMoneyMovements([
            [id: 'mm-in', money_movement_group_id: 'group-in', moved_at: '2026-07-03T12:00:00Z', from_category_id: 'cat-parent-only', to_category_id: 'cat-child-one-spend', amount: 500],
            [id: 'mm-out', money_movement_group_id: 'group-out', moved_at: '2026-07-04T12:00:00Z', from_category_id: 'cat-child-one-spend', to_category_id: 'cat-parent-only', amount: 300]
        ])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-one-mm-1', 'child-one-mm-2'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        List childOnePosts = postedTransactions('child-one-budget-id')
        childOnePosts.size() == 2
        childOnePosts.find { it.memo == 'YBOD: From Parent Only to Child One Spend Bank' && it.amount == 500 && it.payee_name == 'From Parent Only' && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: From Child One Spend Bank to Parent Only' && it.amount == -300 && it.payee_name == 'To Parent Only' && it.cleared == 'cleared' }
        tableCount('sync_mappings') == 2
        cursorValue('transactions.last_server_knowledge') == null
    }

    private ParentChildBudgetSyncer syncer(boolean dryRun) {
        String dbPath = tempDir.resolve('syncstate-wiremock.db').toString()
        syncer(dryRun, new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'YNAB_CHILD_ONE_TOKEN', [['spend-save', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking']]),
                childTarget('child-two', 'Child Two Budget', 'YNAB_CHILD_TWO_TOKEN', [['spend', ['Child Two Spend Bank'], 'Child Two Checking']])
            ],
            300,
            new SyncLoggingConfig(tempDir.resolve('parent-child-sync.log').toString(), 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 45, 45)
        ))
    }

    private ParentChildBudgetSyncer syncer(boolean dryRun, SyncConfig syncConfig) {
        String dbPath = syncConfig.state.sqlitePath
        SyncStateStore stateStore = new SyncStateStore(dbPath)
        stateStore.initialize()
        new ParentChildBudgetSyncer(
            new RuntimeConfig(sync: syncConfig),
            syncConfig,
            dryRun,
            dbPath,
            1,
            new YnabBudgetRepository(buildClient('parent-token')),
            stateStore,
            syncConfig.childBudgets.collect { ChildBudgetSyncTarget target ->
                new ChildSyncContext(target, new YnabBudgetRepository(buildClient(tokenForChild(target.childKey))))
            }
        )
    }

    private static ChildBudgetSyncTarget childTarget(String childKey, String budgetName, String tokenEnvVarName, List mappingRows) {
        new ChildBudgetSyncTarget(
            childKey: childKey,
            budgetName: budgetName,
            tokenEnvVarName: tokenEnvVarName,
            accountMappings: mappingRows.collect { row ->
                new ChildAccountMapping(row[0] as String, (row[1] as List).collect { matcher ->
                    if (matcher instanceof Map) {
                        return new ParentCategoryNameMatcher(matcher.name as String, (matcher.regex ?: false) as Boolean)
                    }
                    new ParentCategoryNameMatcher(matcher as String, false)
                }, row[2] as String)
            }
        )
    }

    private SyncConfig fourChildSyncConfig() {
        new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'YNAB_CHILD_ONE_TOKEN', [
                    ['spend-literal', ['Child One Spend Bank'], 'Child One Spend Account'],
                    ['save-shared', ['Child One Save Bank', [name: 'Child One Bonus.*', regex: true]], 'Child One Save Account'],
                    ['cd-regex', [[name: 'Child One Gold CD.*', regex: true]], 'Child One CD Account']
                ]),
                childTarget('child-two', 'Child Two Budget', 'YNAB_CHILD_TWO_TOKEN', [
                    ['spend-literal', ['Child Two Spend Bank'], 'Child Two Spend Account'],
                    ['give-shared', ['Child Two Give Bank', 'Child Two Charity Bank'], 'Child Two Give Account'],
                    ['cd-regex', [[name: 'Child Two Gold CD.*', regex: true]], 'Child Two CD Account']
                ]),
                childTarget('child-three', 'Child Three Budget', 'YNAB_CHILD_THREE_TOKEN', [
                    ['spend-literal', ['Child Three Spend Bank'], 'Child Three Spend Account'],
                    ['give-literal', ['Child Three Give Bank'], 'Child Three Give Account'],
                    ['cd-regex', [[name: 'Child Three Gold CD.*', regex: true]], 'Child Three CD Account']
                ]),
                childTarget('child-four', 'Child Four Budget', 'YNAB_CHILD_FOUR_TOKEN', [
                    ['spend-shared', ['Child Four Spend Bank', [name: 'Child Four Bonus.*', regex: true]], 'Child Four Spend Account'],
                    ['give-literal', ['Child Four Give Bank'], 'Child Four Give Account'],
                    ['cd-regex', [[name: 'Child Four Gold CD.*', regex: true]], 'Child Four CD Account']
                ])
            ],
            300,
            new SyncLoggingConfig(tempDir.resolve('parent-child-sync.log').toString(), 'INFO', 7, 10),
            new SyncStateConfig(tempDir.resolve('syncstate-wiremock.db').toString(), 45, 45)
        )
    }

    private static String tokenForChild(String childKey) {
        "${childKey}-token"
    }

    private YnabHttpClient buildClient(String token) {
        new YnabHttpClient("http://localhost:${wireMockServer.port()}", token)
    }

    private void stubCommonBudgetDiscovery() {
        stubFor(get(urlEqualTo('/v1/plans'))
            .willReturn(commonBudgetDiscoveryResponse()))
    }

    private void stubCommonBudgetDiscoveryFailureThenSuccess(String failingBudgetId, int status, String detail) {
        int childFailurePriority = failingBudgetId == 'child-one-budget-id' ? 1 : 2
        int successPriority = childFailurePriority == 1 ? 2 : 1
        stubFor(get(urlEqualTo('/v1/plans'))
            .atPriority(childFailurePriority)
            .withHeader('Authorization', equalTo("Bearer ${failingBudgetId == 'child-one-budget-id' ? 'child-one-token' : 'child-two-token'}"))
            .willReturn(errorResponse(status, detail)))
        stubFor(get(urlEqualTo('/v1/plans'))
            .atPriority(successPriority)
            .willReturn(commonBudgetDiscoveryResponse()))
    }

    private static def commonBudgetDiscoveryResponse() {
        jsonResponse([
            data: [budgets: [
                [id: 'parent-budget-id', name: 'Parent Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                [id: 'child-one-budget-id', name: 'Child One Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                [id: 'child-two-budget-id', name: 'Child Two Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                [id: 'child-three-budget-id', name: 'Child Three Budget', last_modified_on: '2026-07-01T12:00:00Z'],
                [id: 'child-four-budget-id', name: 'Child Four Budget', last_modified_on: '2026-07-01T12:00:00Z']
            ]]
        ])
    }

    private void stubParentCategories() {
        stubFor(get(urlEqualTo('/v1/plans/parent-budget-id/categories'))
            .willReturn(jsonResponse([
                data: [category_groups: [[
                    name: 'Kids',
                    categories: [
                        [id: 'cat-child-one-spend', name: 'Child One Spend Bank', balance: 0],
                        [id: 'cat-child-one-save', name: 'Child One Save Bank', balance: 0],
                        [id: 'cat-child-one-bonus', name: 'Child One Bonus Bank', balance: 0],
                        [id: 'cat-child-two-spend', name: 'Child Two Spend Bank', balance: 0],
                        [id: 'cat-child-two-give', name: 'Child Two Give Bank', balance: 0],
                        [id: 'cat-child-two-cd-0726', name: 'Child Two Gold CD 07/31/26', balance: 0],
                        [id: 'cat-child-three-spend', name: 'Child Three Spend Bank', balance: 0],
                        [id: 'cat-child-three-give', name: 'Child Three Give Bank', balance: 0],
                        [id: 'cat-child-three-cd-0726', name: 'Child Three Gold CD 07/31/26', balance: 0],
                        [id: 'cat-child-four-bonus', name: 'Child Four Bonus Bank', balance: 0],
                        [id: 'cat-child-four-give', name: 'Child Four Give Bank', balance: 0],
                        [id: 'cat-child-four-cd-0826', name: 'Child Four Gold CD 08/31/26', balance: 0],
                        [id: 'cat-parent-only', name: 'Parent Only', balance: 0]
                    ]
                ]]]
            ])))
    }

    private void stubParentTransactions(List<Map> transactions, int serverKnowledge, Integer lastKnowledge = null) {
        def mapping = get(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .withQueryParam('since_date', matching('\\d{4}-\\d{2}-\\d{2}'))
        if (lastKnowledge == null) {
            mapping.withQueryParam('last_knowledge_of_server', absent())
        } else {
            mapping.withQueryParam('last_knowledge_of_server', equalTo(lastKnowledge.toString()))
        }
        stubFor(mapping.willReturn(jsonResponse([data: [server_knowledge: serverKnowledge, transactions: transactions]])))
    }

    private void stubMoneyMovements(List<Map> movements) {
        stubFor(get(urlEqualTo('/v1/plans/parent-budget-id/money_movements'))
            .willReturn(jsonResponse([data: [money_movements: movements]])))
    }

    private void stubChildAccounts(String budgetId, String accountId, String accountName) {
        stubChildAccounts(budgetId, [[id: accountId, name: accountName]])
    }

    private void stubChildAccounts(String budgetId, List<Map> accounts) {
        stubFor(get(urlEqualTo("/v1/plans/${budgetId}/accounts"))
            .willReturn(jsonResponse([data: [accounts: accounts]])))
    }

    private void stubChildPost(String budgetId, List<String> transactionIds) {
        stubFor(post(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: transactionIds]]])))
    }

    private void stubChildPostFailure(String budgetId) {
        stubChildPostFailure(budgetId, 500, 'simulated child failure')
    }

    private void stubChildPostFailure(String budgetId, int status, String detail) {
        stubFor(post(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))
            .willReturn(errorResponse(status, detail)))
    }

    private void stubChildPostFailureThenSuccess(String budgetId, List<String> transactionIds) {
        stubFor(post(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))
            .inScenario('retry-child-post')
            .whenScenarioStateIs('Started')
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader('Content-Type', 'application/json')
                .withBody(JsonOutput.toJson([error: [id: '500', detail: 'simulated child failure']])))
            .willSetStateTo('post-succeeds'))
        stubFor(post(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))
            .inScenario('retry-child-post')
            .whenScenarioStateIs('post-succeeds')
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: transactionIds]]])))
    }

    private void stubChildAccountsFailureThenSuccess(String budgetId, String accountId, String accountName) {
        stubFor(get(urlEqualTo("/v1/plans/${budgetId}/accounts"))
            .inScenario('retry-child-account')
            .whenScenarioStateIs('Started')
            .willReturn(jsonResponse([data: [accounts: [[id: 'wrong-account-id', name: 'Wrong Account']]]]))
            .willSetStateTo('account-exists'))
        stubFor(get(urlEqualTo("/v1/plans/${budgetId}/accounts"))
            .inScenario('retry-child-account')
            .whenScenarioStateIs('account-exists')
            .willReturn(jsonResponse([data: [accounts: [[id: accountId, name: accountName]]]])))
    }

    private static def jsonResponse(Object body) {
        aResponse()
            .withStatus(200)
            .withHeader('Content-Type', 'application/json')
            .withBody(JsonOutput.toJson(body))
    }

    private static def errorResponse(int status, String detail) {
        aResponse()
            .withStatus(status)
            .withHeader('Content-Type', 'application/json')
            .withBody(JsonOutput.toJson([error: [id: status.toString(), detail: detail]]))
    }

    private List<Map> postedTransactions(String budgetId) {
        wireMockServer.findAll(postRequestedFor(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))).collectMany { event ->
            new JsonSlurper().parseText(event.bodyAsString).transactions as List<Map>
        }
    }

    private List<String> allPostedImportIds() {
        ['child-one-budget-id', 'child-two-budget-id', 'child-three-budget-id', 'child-four-budget-id'].collectMany { postedTransactions(it) }*.import_id
    }

    private int tableCount(String tableName) {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM ${tableName}")
            rs.next()
            rs.getInt(1)
        }
    }

    private Integer cursorValue(String key) {
        withDb { connection ->
            def statement = connection.prepareStatement('SELECT value_integer FROM sync_cursors WHERE key = ?')
            statement.setString(1, key)
            def rs = statement.executeQuery()
            rs.next() ? rs.getInt(1) : null
        }
    }

    private List<Map> appliedRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('SELECT status, failure_reason FROM applied_transactions ORDER BY id')
            List rows = []
            while (rs.next()) {
                rows << [status: rs.getString('status'), failure_reason: rs.getString('failure_reason')]
            }
            rows
        }
    }

    private List<Map> mappingRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('''
                SELECT target_child_key, target_mapping_key, target_account_name, target_account_id, idempotency_key
                FROM sync_mappings
                ORDER BY id
            ''')
            List rows = []
            while (rs.next()) {
                rows << [
                    target_child_key : rs.getString('target_child_key'),
                    target_mapping_key: rs.getString('target_mapping_key'),
                    target_account_name: rs.getString('target_account_name'),
                    target_account_id: rs.getString('target_account_id'),
                    idempotency_key: rs.getString('idempotency_key')
                ]
            }
            rows
        }
    }

    private <T> T withDb(Closure<T> closure) {
        def connection = DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve('syncstate-wiremock.db')}")
        try {
            closure.call(connection)
        } finally {
            connection.close()
        }
    }
}
