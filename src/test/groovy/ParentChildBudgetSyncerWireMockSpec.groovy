import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
        tableCount('sync_operations') == 5
        tableCount('operation_attempts') == 5
        tableCount('child_mirrors') == 5
        cursorValue('transactions.last_server_knowledge') == 41
    }

    def "live child post uses custom memo decoration and cleared status"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-custom-memo', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 42)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-one-created-custom'])

        when:
        syncer(false, syncConfig('[Kid] ', ' (auto)')).runOnce(1)

        then:
        postedTransactions('child-one-budget-id') == [[
            account_id: 'child-one-account-id',
            date: '2026-07-01',
            amount: -1200,
            payee_id: null,
            payee_name: null,
            category_id: null,
            memo: '[Kid] Shoes (auto)',
            cleared: 'cleared',
            approved: false,
            import_id: postedTransactions('child-one-budget-id')[0].import_id
        ]]
        verify(0, getRequestedFor(urlEqualTo(
            '/v1/plans/parent-budget-id/transactions/txn-custom-memo')))
        tableCount('operation_attempts') == 1
    }

    def "bootstrap listing treats list payload as complete and skips per-id parent fetches"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-ordinary-1', date: '2026-07-01', amount: -100, memo: 'One', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-ordinary-2', date: '2026-07-01', amount: -200, memo: 'Two', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-split-bootstrap', date: '2026-07-02', amount: -300, memo: 'Split', approved: true,
             category_id: null, category_name: null, subtransactions: [
                [id: 'sub-bootstrap-save', transaction_id: 'txn-split-bootstrap', amount: -300, memo: 'Save',
                 category_id: 'cat-child-one-save', category_name: 'Child One Save Bank']
            ]]
        ], 501)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-1', 'child-2', 'child-3'])

        when:
        syncer(false).runOnce(1)

        then:
        postedTransactions('child-one-budget-id').size() == 3
        verify(0, getRequestedFor(urlPathMatching('/v1/plans/parent-budget-id/transactions/.+')))
        cursorValue('transactions.last_server_knowledge') == 501
    }

    def "incremental delta fetches parent detail only when a split mirror would be deleted from absence"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        Map subSave = [id: 'sub-keep', transaction_id: 'txn-split-detail', amount: -400, memo: 'Keep',
                       category_id: 'cat-child-one-save', category_name: 'Child One Save Bank']
        Map subSpend = [id: 'sub-drop', transaction_id: 'txn-split-detail', amount: -500, memo: 'Drop',
                        category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank']
        Map fullSplit = [id: 'txn-split-detail', date: '2026-07-01', amount: -900, memo: 'Split',
                         approved: true, category_id: null, category_name: null,
                         subtransactions: [subSave, subSpend]]
        Map partialSplit = fullSplit + [amount: -400, subtransactions: [subSave]]
        stubParentTransactions([fullSplit], 601)
        stubParentTransactions([partialSplit], 602, 601)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostsInOrder('child-one-budget-id', ['child-keep', 'child-drop'])
        stubChildDelete('child-one-budget-id', 'child-drop')
        stubChildLookup('child-one-budget-id', 'child-keep', 'child-one-account-id',
            '2026-07-01', -400, null, null)

        when:
        def syncer = syncer(false)
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        verify(1, getRequestedFor(urlEqualTo('/v1/plans/parent-budget-id/transactions/txn-split-detail')))
        verify(1, deleteRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-drop')))
        cursorValue('transactions.last_server_knowledge') == 602
    }

    def "incremental ordinary parent edit does not fetch the transaction by id"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-ordinary-edit', date: '2026-07-01', amount: -1000, memo: 'Original', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 701)
        stubParentTransactions([[
            id: 'txn-ordinary-edit', date: '2026-07-02', amount: -1100, memo: 'Original', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 702, 701)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-ordinary-edit'])
        stubFor(get(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-ordinary-edit'))
            .willReturn(jsonResponse([data: [server_knowledge: 1, transaction: [
                id: 'child-ordinary-edit', account_id: 'child-one-account-id', date: '2026-07-01', amount: -1000,
                payee_id: null, payee_name: null, memo: 'YBOD: Original',
                cleared: 'cleared', approved: false, deleted: false
            ]]])))
        stubFor(put(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-ordinary-edit'))
            .willReturn(jsonResponse([data: [server_knowledge: 2, transaction: [
                id: 'child-ordinary-edit', account_id: 'child-one-account-id', date: '2026-07-02', amount: -1100,
                payee_id: null, payee_name: null, memo: 'YBOD: Original',
                cleared: 'cleared', approved: false, deleted: false
            ]]])))

        when:
        def syncer = syncer(false)
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        verify(0, getRequestedFor(urlPathMatching('/v1/plans/parent-budget-id/transactions/.+')))
        cursorValue('transactions.last_server_knowledge') == 702
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
        childOnePosts.every { it.approved == false }
        childOnePosts.find { it.memo == 'YBOD: Child one shoes' && it.account_id == 'child-one-spend-account-id' && it.amount == -1100 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: Child one bonus' && it.account_id == 'child-one-save-account-id' && it.amount == -1200 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: Child one split save' && it.account_id == 'child-one-save-account-id' && it.amount == -600 && it.category_id == null && it.cleared == 'cleared' }
        childOnePosts.find { it.memo == 'YBOD: From Child One Save Bank to Child Two Spend Bank' && it.account_id == 'child-one-save-account-id' && it.amount == -250 && it.payee_name == 'To Child Two Spend Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        List childTwoPosts = postedTransactions('child-two-budget-id')
        childTwoPosts.size() == 3
        childTwoPosts.every { it.approved == false }
        childTwoPosts.find { it.memo == 'YBOD: Child two CD' && it.account_id == 'child-two-cd-account-id' && it.amount == -1300 && it.category_id == null && it.cleared == 'cleared' }
        childTwoPosts.find { it.memo == 'YBOD: Child two split give' && it.account_id == 'child-two-give-account-id' && it.amount == -700 && it.category_id == null && it.cleared == 'cleared' }
        childTwoPosts.find { it.memo == 'YBOD: From Child One Save Bank to Child Two Spend Bank' && it.account_id == 'child-two-spend-account-id' && it.amount == 250 && it.payee_name == 'From Child One Save Bank' && it.category_id == null && it.cleared == 'cleared' }
        !childTwoPosts.find { it.memo == 'Ignore unapproved' }

        and:
        List childThreePosts = postedTransactions('child-three-budget-id')
        childThreePosts.size() == 3
        childThreePosts.every { it.approved == false }
        childThreePosts.find { it.memo == 'YBOD: Child three give' && it.account_id == 'child-three-give-account-id' && it.amount == -1400 && it.category_id == null && it.cleared == 'cleared' }
        childThreePosts.find { it.memo == 'YBOD: Four child split memo' && it.account_id == 'child-three-spend-account-id' && it.amount == -800 && it.category_id == null && it.cleared == 'cleared' }
        childThreePosts.find { it.memo == 'YBOD: From Child Three Gold CD 07/31/26 to Child Four Give Bank' && it.account_id == 'child-three-cd-account-id' && it.amount == -350 && it.payee_name == 'To Child Four Give Bank' && it.category_id == null && it.cleared == 'cleared' }

        and:
        List childFourPosts = postedTransactions('child-four-budget-id')
        childFourPosts.size() == 3
        childFourPosts.every { it.approved == false }
        childFourPosts.find { it.memo == 'YBOD: Child four bonus' && it.account_id == 'child-four-spend-account-id' && it.amount == -1500 && it.category_id == null && it.cleared == 'cleared' }
        childFourPosts.find { it.memo == 'YBOD: Child four split CD' && it.account_id == 'child-four-cd-account-id' && it.amount == -900 && it.category_id == null && it.cleared == 'cleared' }
        childFourPosts.find { it.memo == 'YBOD: From Child Three Gold CD 07/31/26 to Child Four Give Bank' && it.account_id == 'child-four-give-account-id' && it.amount == 350 && it.payee_name == 'From Child Three Gold CD 07/31/26' && it.category_id == null && it.cleared == 'cleared' }

        and:
        verify(getRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        verify(getRequestedFor(urlEqualTo('/v1/plans/child-two-budget-id/accounts')))
        verify(getRequestedFor(urlEqualTo('/v1/plans/child-three-budget-id/accounts')))
        verify(getRequestedFor(urlEqualTo('/v1/plans/child-four-budget-id/accounts')))
        tableCount('sync_runs') == 1
        tableCount('sync_operations') == 13
        tableCount('operation_attempts') == 13
        tableCount('child_mirrors') == 13
        cursorValue('transactions.last_server_knowledge') == 101
    }

    def "second live cycle uses saved transaction cursor and durable reconciliation state prevents reposting"() {
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
        stubChildLookup('child-one-budget-id', 'child-one-created-1', 'child-one-account-id',
            '2026-07-01', -1200, null, null)
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 1
        tableCount('sync_runs') == 2
        tableCount('source_entities') == 1
        tableCount('source_revisions') == 1
        tableCount('sync_operations') == 2
        tableCount('operation_attempts') == 2
        cursorValue('transactions.last_server_knowledge') == 42
        verify(getRequestedFor(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .withQueryParam('last_knowledge_of_server', equalTo('41')))
    }

    def "three process-like cycles preserve partial success retry failure and apply only incremental work"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-cycle-one-applied', date: '2026-07-10', amount: -1000, memo: 'First', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-cycle-one-retry', date: '2026-07-10', amount: -2000, memo: 'Retry', approved: true, category_id: 'cat-child-one-save', category_name: 'Child One Save Bank', subtransactions: []]
        ], 100)
        stubParentTransactions([
            [id: 'txn-cycle-three-new', date: '2026-07-11', amount: -3000, memo: 'New incremental work', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 101, 100)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostSuccessFailureSuccessSuccess('child-one-budget-id')
        stubChildLookup('child-one-budget-id', 'created-first', 'child-one-account-id',
            '2026-07-10', -1000, null, null)

        when:
        syncer(false).runOnce(1)

        then:
        postedTransactions('child-one-budget-id').size() == 2
        appliedRows()*.status == ['applied', 'failed']
        runRows()*.status == ['partial']
        cursorValue('transactions.last_server_knowledge') == null

        when:
        syncer(false).runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 3
        appliedRows()*.status == ['applied', 'failed', 'applied', 'applied']
        runRows()*.status == ['partial', 'succeeded']
        cursorValue('transactions.last_server_knowledge') == 100

        when:
        syncer(false).runOnce(3)

        then:
        List posts = postedTransactions('child-one-budget-id')
        posts.size() == 4
        posts*.memo == ['YBOD: First', 'YBOD: Retry', 'YBOD: Retry', 'YBOD: New incremental work']
        posts[1].import_id == posts[2].import_id
        posts[0].import_id != posts[3].import_id
        appliedRows()*.status == ['applied', 'failed', 'applied', 'applied', 'applied']
        appliedRows().findAll { it.status == 'applied' }*.created_child_transaction_id ==
            ['created-first', 'created-first', 'created-retry', 'created-new']
        runRows()*.status == ['partial', 'succeeded', 'succeeded']
        runRows()[0].error_summary.contains('YNAB POST transactions failed with status 500')
        !runRows()[0].error_summary.contains('/v1/plans')
        !runRows()[0].error_summary.contains('simulated second post failure')
        runRows()[1..2]*.error_summary == [null, null]
        tableCount('source_entities') == 3
        tableCount('child_mirrors') == 3
        tableCount('operation_attempts') == 5
        cursorValue('transactions.last_server_knowledge') == 101
        verify(getRequestedFor(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .withQueryParam('last_knowledge_of_server', equalTo('100')))
    }

    def "simulated dry run plans custom decorated cleared payload without posting or mutating SQLite state"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-approved', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 41)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        Logger logger = (Logger) LoggerFactory.getLogger(ParentChildBudgetSyncer)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        def syncer = syncer(true, syncConfig('[Kid] ', ' (auto)'))
        byte[] databaseBefore = Files.readAllBytes(tempDir.resolve('syncstate-wiremock.db'))

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk')))
        appender.list*.formattedMessage.any {
            it.contains('[DRY RUN] create child transaction for child-one') &&
                it.contains('"memo":"[Kid] Shoes (auto)"') &&
                it.contains('"cleared":"cleared"')
        }
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
        Files.readAllBytes(tempDir.resolve('syncstate-wiremock.db')) == databaseBefore

        cleanup:
        logger.detachAppender(appender)
    }

    def "child API failure records retryable operation state and continues other child targets"() {
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
        appliedRows().find { it.status == 'failed' }.failure_reason ==
            'YNAB POST transactions failed with status 500'
        !appliedRows().find { it.status == 'failed' }.failure_reason.contains('child-one-budget-id')
        !appliedRows().find { it.status == 'failed' }.failure_reason.contains('simulated child failure')
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "successful child post response without a transaction id is recorded as failed"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-missing-created-id', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true, category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 50)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubFor(post(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk'))
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: []]]])))

        when:
        syncer(false).runOnce(1)

        then:
        appliedRows()*.status == ['failed']
        appliedRows()[0].failure_reason.contains('did not include a created transaction ID')
        runRows()*.status == ['partial']
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
        runRows()[0].error_summary.contains("Could not find account named 'Child One Checking'")
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "live cycle auto-creates a missing child checking account then mirrors the parent transaction"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-auto-create', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-unmapped-auto-create', date: '2026-07-01', amount: -1400, memo: 'Parent only', approved: true,
             category_id: 'cat-parent-only', category_name: 'Parent Only', subtransactions: []]
        ], 401)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', [])
        stubCreateAccount('child-one-budget-id', 'Child One Spend', 'created-spend-account-id', 'checking', true)
        stubChildPost('child-one-budget-id', ['child-one-created-auto'])
        def syncer = syncer(false, autoCreateSyncConfig())

        when:
        syncer.runOnce(1)

        then:
        List creates = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        creates.size() == 1
        new JsonSlurper().parseText(creates[0].bodyAsString) == [
            account: [name: 'Child One Spend', type: 'checking', balance: 0]
        ]
        postedTransactions('child-one-budget-id') == [[
            account_id: 'created-spend-account-id',
            date: '2026-07-01',
            amount: -1200,
            payee_id: null,
            payee_name: null,
            category_id: null,
            memo: 'YBOD: Shoes',
            cleared: 'cleared',
            approved: false,
            import_id: postedTransactions('child-one-budget-id')[0].import_id
        ]]
        tableCount('child_mirrors') == 1
        cursorValue('transactions.last_server_knowledge') == 401
    }

    def "live cycle auto-creates an off-budget otherAsset account when createdAccountOnBudget is false"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-auto-create-tracking', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 405)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', [])
        stubCreateAccount('child-one-budget-id', 'Child One Spend', 'created-asset-account-id', 'otherAsset', false)
        stubChildPost('child-one-budget-id', ['child-one-created-asset'])
        def syncer = syncer(false, autoCreateSyncConfig(true, false))

        when:
        syncer.runOnce(1)

        then:
        List creates = wireMockServer.findAll(postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        creates.size() == 1
        new JsonSlurper().parseText(creates[0].bodyAsString) == [
            account: [name: 'Child One Spend', type: 'otherAsset', balance: 0]
        ]
        postedTransactions('child-one-budget-id')[0].account_id == 'created-asset-account-id'
        cursorValue('transactions.last_server_knowledge') == 405
    }

    def "live auto-create logs INFO with account name, budget name, and type"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-auto-create-log', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 406)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', [])
        stubCreateAccount('child-one-budget-id', 'Child One Spend', 'created-log-account-id', accountType, onBudget)
        stubChildPost('child-one-budget-id', ['child-one-created-log'])
        Logger logger = (Logger) LoggerFactory.getLogger(ParentChildBudgetSyncer)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        def syncer = syncer(false, autoCreateSyncConfig(true, onBudget))

        when:
        syncer.runOnce(1)

        then:
        appender.list.any {
            it.level == Level.INFO &&
                it.formattedMessage == "Creating New YNAB Account: Child One Spend in Budget: Child One Budget with type: ${accountType}"
        }

        cleanup:
        logger.detachAppender(appender)

        where:
        accountType  | onBudget
        'checking'   | true
        'otherAsset' | false
    }

    def "dry-run auto-create logs a planned checking account and does not POST create or child transactions"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-auto-create-dry', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 402)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', [])
        Logger logger = (Logger) LoggerFactory.getLogger(ParentChildBudgetSyncer)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        def syncer = syncer(true, autoCreateSyncConfig())

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk')))
        appender.list*.formattedMessage.any {
            it.contains("Would create account 'Child One Spend' type=checking") &&
                it.contains('createdAccountOnBudget=true') &&
                it.contains('child-one')
        }
        tableCount('sync_runs') == 0

        cleanup:
        logger.detachAppender(appender)
    }

    def "auto-create reuses an existing derived-name account without a second create POST"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-reuse-derived', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 403)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'existing-derived-id', 'Child One Spend')
        stubChildPost('child-one-budget-id', ['child-one-reused'])
        def syncer = syncer(false, autoCreateSyncConfig())

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        postedTransactions('child-one-budget-id')[0].account_id == 'existing-derived-id'
    }

    def "autoCreateAccounts false still fails missing mapped accounts without creating"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-no-auto-create', date: '2026-07-01', amount: -1200, memo: 'Shoes', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []]
        ], 404)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', [])
        def syncer = syncer(false, autoCreateSyncConfig(false))

        when:
        syncer.runOnce(1)

        then:
        verify(0, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/accounts')))
        runRows()[0].error_summary.contains("Could not find account named 'Child One Checking'")
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
        appliedRows()*.status == ['applied']
        cursorValue('transactions.last_server_knowledge') == 62
    }

    def "transient routing failure cannot delete existing transaction or movement mirrors"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-routing-safe', date: '2026-07-01', amount: -1000, memo: 'Safe', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 401)
        stubParentTransactions([[
            id: 'txn-routing-safe', date: '2026-07-01', amount: -1000, memo: 'Safe', approved: true,
            category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []
        ]], 402, 401)
        stubMoneyMovementsInOrder([[
            id: 'movement-routing-safe', money_movement_group_id: 'group', moved_at: '2026-07-01T00:00:00Z',
            from_category_id: 'cat-parent-only', to_category_id: 'cat-child-one-spend', amount: 500
        ]], [[
            id: 'movement-routing-safe', money_movement_group_id: 'group', moved_at: '2026-07-01T00:00:00Z',
            from_category_id: 'cat-parent-only', to_category_id: 'cat-child-two-spend', amount: 500
        ]])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'wrong-account-id', 'Wrong Account')
        stubChildPostsInOrder('child-one-budget-id', ['transaction-child', 'movement-child'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        verify(0, deleteRequestedFor(urlMatching('/v1/plans/.*/transactions/.*')))
        mirrorRows()*.child_transaction_id == ['transaction-child', 'movement-child']
        mirrorRows()*.status == ['active', 'active']
        sourceLifecycleRows().every { it.lifecycle_status == 'active' }
        ingestionBatchRows().findAll { it.server_knowledge == 402 || it.source_kind == 'money_movement_snapshot' }
            .any { it.status == 'pending' }
        cursorValue('transactions.last_server_knowledge') == 401
        runRows().last().status == 'partial'
    }

    def "routing failure for one child does not mark an unrelated resolved source deleted"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([
            [id: 'txn-child-one-ok', date: '2026-07-01', amount: -1100, memo: 'One', approved: true,
             category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []],
            [id: 'txn-child-two-blocked', date: '2026-07-01', amount: -2200, memo: 'Two', approved: true,
             category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []]
        ], 410)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubFor(get(urlEqualTo('/v1/plans/child-two-budget-id/accounts'))
            .willReturn(errorResponse(500, 'child two routing unavailable')))
        stubChildPost('child-one-budget-id', ['child-one-created'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)

        then:
        verify(1, postRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/bulk')))
        verify(0, deleteRequestedFor(urlMatching('/v1/plans/.*/transactions/.*')))
        mirrorRows()*.child_transaction_id == ['child-one-created']
        sourceLifecycleRows().findAll { it.parent_transaction_id == 'txn-child-one-ok' }*.lifecycle_status == ['active']
        sourceLifecycleRows().findAll { it.parent_transaction_id == 'txn-child-two-blocked' }*.lifecycle_status == ['active']
        cursorValue('transactions.last_server_knowledge') == null
        runRows().last().status == 'partial'
    }

    def "routing failure isolates components within one split and recovery creates only the missing child mirror"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-split-routing-isolation', date: '2026-07-01', amount: -1100,
            memo: 'Split', approved: true, category_id: null, category_name: null,
            subtransactions: [[
                id: 'sub-child-one-existing', transaction_id: 'txn-split-routing-isolation', amount: -1100,
                memo: 'Existing child one', category_id: 'cat-child-one-spend',
                category_name: 'Child One Spend Bank'
            ]]
        ]], 420)
        stubParentTransactions([[
            id: 'txn-split-routing-isolation', date: '2026-07-01', amount: -3300,
            memo: 'Split', approved: true, category_id: null, category_name: null,
            subtransactions: [
                [id: 'sub-child-one-existing', transaction_id: 'txn-split-routing-isolation', amount: -1100,
                 memo: 'Existing child one', category_id: 'cat-child-one-spend',
                 category_name: 'Child One Spend Bank'],
                [id: 'sub-child-one-missing', transaction_id: 'txn-split-routing-isolation', amount: -1000,
                 memo: 'Missing child one', category_id: 'cat-child-one-save',
                 category_name: 'Child One Save Bank'],
                [id: 'sub-child-two-healthy', transaction_id: 'txn-split-routing-isolation', amount: -1200,
                 memo: 'Healthy child two', category_id: 'cat-child-two-spend',
                 category_name: 'Child Two Spend Bank']
            ]
        ]], 421, 420)
        stubMoneyMovements([])
        stubChildAccountsSuccessFailureSuccess('child-one-budget-id',
            [[id: 'child-one-spend-account-id', name: 'Child One Spend Account']],
            [[id: 'child-one-spend-account-id', name: 'Child One Spend Account'],
             [id: 'child-one-save-account-id', name: 'Child One Save Account']])
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPostsInOrder('child-one-budget-id', ['child-one-existing', 'child-one-restored'])
        stubChildPost('child-two-budget-id', ['child-two-created'])
        stubChildLookup('child-one-budget-id', 'child-one-existing', 'child-one-spend-account-id',
            '2026-07-01', -1100, null, null)
        stubChildLookup('child-two-budget-id', 'child-two-created', 'child-two-account-id',
            '2026-07-01', -1200, null, null)
        SyncConfig config = splitRoutingIsolationConfig()
        def syncer = syncer(false, config)

        when: 'the original child-one component is mirrored'
        syncer.runOnce(1)

        then:
        postedTransactions('child-one-budget-id')*.memo == ['YBOD: Existing child one']

        when: 'child-one routing fails for the expanded split'
        syncer.runOnce(2)

        then: 'the healthy child-two component is created and the existing child-one mirror is preserved'
        postedTransactions('child-one-budget-id')*.memo == ['YBOD: Existing child one']
        postedTransactions('child-two-budget-id')*.memo == ['YBOD: Healthy child two']
        verify(0, deleteRequestedFor(urlEqualTo(
            '/v1/plans/child-one-budget-id/transactions/child-one-existing')))
        mirrorRows().collectEntries { [(it.child_transaction_id): it.status] } ==
            ['child-one-existing': 'active', 'child-two-created': 'active']
        cursorValue('transactions.last_server_knowledge') == 420

        when: 'child-one routing is restored'
        syncer.runOnce(3)

        then: 'only the missing child-one component is created'
        postedTransactions('child-one-budget-id')*.memo ==
            ['YBOD: Existing child one', 'YBOD: Missing child one']
        postedTransactions('child-two-budget-id')*.memo == ['YBOD: Healthy child two']
        mirrorRows().collectEntries { [(it.child_transaction_id): it.status] } ==
            ['child-one-existing': 'active', 'child-two-created': 'active', 'child-one-restored': 'active']
        cursorValue('transactions.last_server_knowledge') == 421
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
        appliedRows()*.status == ['applied']
        runRows()[0].error_summary.contains('YNAB GET accounts failed with status 401')
        !runRows()[0].error_summary.contains('child-one-budget-id')
        !runRows()[0].error_summary.contains('unauthorized child token')
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
        appliedRows()*.status == ['applied']
        runRows()[0].error_summary.contains('YNAB GET plans failed with status 403')
        !runRows()[0].error_summary.contains('/v1/plans')
        !runRows()[0].error_summary.contains('forbidden child plan discovery')
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
        appliedRows().find { it.status == 'failed' }.failure_reason ==
            'YNAB POST transactions failed with status 403'
        !appliedRows().find { it.status == 'failed' }.failure_reason.contains('child-one-budget-id')
        !appliedRows().find { it.status == 'failed' }.failure_reason.contains('forbidden child transaction post')
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
        ex.message == 'YNAB GET plans failed with status 503'
        !ex.message.contains('/v1/plans')
        !ex.message.contains('parent plan list outage')
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
        ex.message == 'YNAB GET categories failed with status 503'
        !ex.message.contains('parent-budget-id')
        !ex.message.contains('category outage')
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
        ex.message == 'YNAB GET transactions failed with status 500'
        !ex.message.contains('parent-budget-id')
        !ex.message.contains('transaction outage')
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        tableCount('sync_runs') == 0
        cursorValue('transactions.last_server_knowledge') == null
    }

    def "parent money movement failure does not block transaction cursor"() {
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
        verify(0, postRequestedFor(urlMatching('/v1/plans/.*/transactions/bulk')))
        runRows()*.status == ['partial']
        runRows()[0].error_summary.contains('YNAB GET plans failed with status 500')
        !runRows()[0].error_summary.contains('parent-budget-id')
        !runRows()[0].error_summary.contains('money movement outage')
        cursorValue('transactions.last_server_knowledge') == 81
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
        tableCount('sync_operations') == 2
        cursorValue('transactions.last_server_knowledge') == 92
    }

    def "no-op parent edit recreates a missing mirror and advances cursor"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-recreate', date: '2026-07-01', amount: -1200, memo: 'Original', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 201)
        stubParentTransactions([[
            id: 'txn-recreate', date: '2026-07-01', amount: -1200, memo: 'Memo edit', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 202, 201)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostsInOrder('child-one-budget-id', ['original-child', 'replacement-child'])
        stubChildMissing('child-one-budget-id', 'original-child')
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 2
        mirrorRows()*.child_transaction_id == ['original-child', 'replacement-child']
        mirrorRows()*.status == ['missing', 'active']
        cursorValue('transactions.last_server_knowledge') == 202
    }

    def "tombstone unapproval and unmapping delete mirrors without reversals"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        List initial = ['deleted', 'unapproved', 'unmapped'].collect { suffix -> [
            id: "txn-${suffix}", date: '2026-07-01', amount: -1000, memo: suffix, approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ] }
        stubParentTransactions(initial, 211)
        stubParentTransactions([
            initial[0] + [deleted: true],
            initial[1] + [approved: false],
            initial[2] + [category_id: 'cat-parent-only', category_name: 'Parent Only']
        ], 212, 211)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostsInOrder('child-one-budget-id', ['child-deleted', 'child-unapproved', 'child-unmapped'])
        ['child-deleted', 'child-unapproved', 'child-unmapped'].each { stubChildDelete('child-one-budget-id', it) }
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        postedTransactions('child-one-budget-id').size() == 3
        verify(3, deleteRequestedFor(urlMatching('/v1/plans/child-one-budget-id/transactions/.*')))
        mirrorRows()*.status == ['deleted', 'deleted', 'deleted']
        sourceLifecycleRows()*.lifecycle_status == ['deleted', 'deleted', 'deleted']
        cursorValue('transactions.last_server_knowledge') == 212
    }

    def "movement mutation failure remains retryable while transaction cursor advances"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-independent', date: '2026-07-01', amount: -1000, memo: 'Transaction', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 221)
        stubMoneyMovements([[
            id: 'movement-fails', money_movement_group_id: 'group', moved_at: '2026-07-02T00:00:00Z',
            from_category_id: 'cat-parent-only', to_category_id: 'cat-child-one-spend', amount: 500
        ]])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPostSuccessThenFailure('child-one-budget-id', 'transaction-child')

        when:
        syncer(false).runOnce(1)

        then:
        appliedRows()*.status == ['applied', 'failed']
        runRows()*.status == ['partial']
        cursorValue('transactions.last_server_knowledge') == 221
        operationRows().find { it.source_kind == 'money_movement_snapshot' }.status == 'retryable_failed'
    }

    def "authoritative edit updates one child preserves memo and stable lineage"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-update', date: '2026-07-01', amount: -1000, memo: 'Parent original', approved: true,
            payee_id: null, payee_name: 'Old Payee', category_id: 'cat-child-one-spend',
            category_name: 'Child One Spend Bank', subtransactions: []
        ]], 291)
        stubParentTransactions([[
            id: 'txn-update', date: '2026-07-02', amount: -1250, memo: 'Parent changed', approved: true,
            payee_id: null, payee_name: 'New Payee', category_id: 'cat-child-one-spend',
            category_name: 'Child One Spend Bank', subtransactions: []
        ]], 292, 291)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildPost('child-one-budget-id', ['child-update'])
        stubFor(get(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-update'))
            .willReturn(jsonResponse([data: [server_knowledge: 1, transaction: [
                id: 'child-update', account_id: 'child-one-account-id', date: '2026-07-01', amount: -1000,
                payee_id: null, payee_name: 'Old Payee', memo: 'child-owned memo',
                cleared: 'uncleared', approved: true, deleted: false
            ]]])))
        stubFor(put(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-update'))
            .willReturn(jsonResponse([data: [server_knowledge: 2, transaction: [
                id: 'child-update', account_id: 'child-one-account-id', date: '2026-07-02', amount: -1250,
                payee_id: null, payee_name: 'New Payee', memo: 'child-owned memo',
                cleared: 'cleared', approved: false, deleted: false
            ]]])))
        def syncer = syncer(false)
        def auditAppender = new ListAppender<ILoggingEvent>()
        auditAppender.start()
        Logger syncerLogger = LoggerFactory.getLogger(ParentChildBudgetSyncer) as Logger
        syncerLogger.addAppender(auditAppender)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)
        List<String> auditMessages = auditAppender.list.findAll { it.level.levelStr == 'INFO' }*.formattedMessage

        then:
        postedTransactions('child-one-budget-id').size() == 1
        verify(putRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/child-update'))
            .withHeader('Authorization', equalTo('Bearer child-one-token'))
            .withRequestBody(equalToJson(JsonOutput.toJson([transaction: [
                account_id: 'child-one-account-id', date: '2026-07-02', amount: -1250,
                payee_id: null, payee_name: 'New Payee', cleared: 'cleared', approved: false
            ]]))))
        tableCount('source_entities') == 1
        tableCount('source_revisions') == 2
        mirrorRows() == [[child_transaction_id: 'child-update', status: 'active']]
        cursorValue('transactions.last_server_knowledge') == 292
        auditMessages.any {
            it.contains('Reconciliation decision action=create') &&
                it.contains('transaction=txn-update') && it.contains('targetBudget=child-one-budget-id')
        }
        auditMessages.any {
            it.contains('Reconciliation decision action=update') &&
                it.contains('childTransaction=child-update') && it.contains('"amount":"-$1.25"')
        }
        auditMessages.findAll { it.contains('Reconciliation decision') }.every {
            it.contains('operation=') && it.contains('key=') && it.contains('batch=') &&
                it.contains('sequence=') && it.contains('dependency=') && !it.contains('child-one-token')
        }

        cleanup:
        syncerLogger.detachAppender(auditAppender)
    }

    def "cross-budget reroute separates tokens deletes once and retries only replacement create"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-reroute', date: '2026-07-01', amount: -1000, memo: 'Reroute', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 301)
        stubParentTransactions([[
            id: 'txn-reroute', date: '2026-07-01', amount: -1000, memo: 'Reroute', approved: true,
            category_id: 'cat-child-two-spend', category_name: 'Child Two Spend Bank', subtransactions: []
        ]], 302, 301)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-one-budget-id', ['old-child'])
        stubChildDelete('child-one-budget-id', 'old-child')
        stubChildPostFailureThenSuccess('child-two-budget-id', ['replacement-child'])
        def syncer = syncer(false)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        cursorValue('transactions.last_server_knowledge') == 301
        verify(deleteRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/old-child'))
            .withHeader('Authorization', equalTo('Bearer child-one-token')))
        verify(postRequestedFor(urlEqualTo('/v1/plans/child-two-budget-id/transactions/bulk'))
            .withHeader('Authorization', equalTo('Bearer child-two-token')))

        when:
        syncer.runOnce(3)

        then:
        verify(1, deleteRequestedFor(urlEqualTo('/v1/plans/child-one-budget-id/transactions/old-child')))
        verify(2, postRequestedFor(urlEqualTo('/v1/plans/child-two-budget-id/transactions/bulk')))
        mirrorRows()*.child_transaction_id == ['old-child', 'replacement-child']
        mirrorRows()*.status == ['deleted', 'active']
        sourceLifecycleRows()*.lifecycle_status == ['active']
        cursorValue('transactions.last_server_knowledge') == 302
    }

    def "partial target deletion keeps a still-qualified source active"() {
        given:
        stubCommonBudgetDiscovery()
        stubParentCategories()
        stubParentTransactions([[
            id: 'txn-partial-target', date: '2026-07-01', amount: -1000, memo: 'Shared', approved: true,
            category_id: 'cat-child-one-spend', category_name: 'Child One Spend Bank', subtransactions: []
        ]], 311)
        stubParentTransactions([[
            id: 'txn-partial-target', date: '2026-07-01', amount: -1000, memo: 'Shared', approved: true,
            category_id: 'cat-child-one-save', category_name: 'Child One Save Bank', subtransactions: []
        ]], 312, 311)
        stubMoneyMovements([])
        stubChildAccounts('child-one-budget-id', 'child-one-account-id', 'Child One Checking')
        stubChildAccounts('child-two-budget-id', 'child-two-account-id', 'Child Two Checking')
        stubChildPost('child-one-budget-id', ['child-one-shared'])
        stubChildPost('child-two-budget-id', ['child-two-shared'])
        stubChildLookup('child-one-budget-id', 'child-one-shared', 'child-one-account-id',
            '2026-07-01', -1000, null, null)
        stubChildDelete('child-two-budget-id', 'child-two-shared')
        SyncConfig config = syncConfig()
        ChildBudgetSyncTarget childTwo = config.childBudgets[1]
        ChildAccountMapping originalMapping = childTwo.accountMappings[0]
        ChildAccountMapping expandedMapping = new ChildAccountMapping(
            originalMapping.mappingKey,
            originalMapping.parentCategoryNames + new ParentCategoryNameMatcher('Child One Spend Bank', false),
            originalMapping.childAccountName)
        ChildBudgetSyncTarget expandedChildTwo = new ChildBudgetSyncTarget(
            childKey: childTwo.childKey,
            budgetName: childTwo.budgetName,
            tokenEnvVarName: childTwo.tokenEnvVarName,
            accountMappings: [expandedMapping],
            memoPrefix: childTwo.memoPrefix,
            memoSuffix: childTwo.memoSuffix
        )
        config = new SyncConfig(config.parentBudget, [config.childBudgets[0], expandedChildTwo],
            config.pollingIntervalSeconds, config.logging, config.state)
        def syncer = syncer(false, config)

        when:
        syncer.runOnce(1)
        syncer.runOnce(2)

        then:
        verify(1, deleteRequestedFor(urlEqualTo(
            '/v1/plans/child-two-budget-id/transactions/child-two-shared')))
        sourceLifecycleRows()*.lifecycle_status == ['active']
        mirrorRows().collectEntries { [(it.child_transaction_id): it.status] } ==
            ['child-one-shared': 'active', 'child-two-shared': 'deleted']
        cursorValue('transactions.last_server_knowledge') == 312
    }

    private ParentChildBudgetSyncer syncer(boolean dryRun) {
        syncer(dryRun, syncConfig())
    }

    private SyncConfig syncConfig(String memoPrefix = 'YBOD: ', String memoSuffix = '') {
        String dbPath = tempDir.resolve('syncstate-wiremock.db').toString()
        new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'YNAB_CHILD_ONE_TOKEN', [['spend-save', ['Child One Spend Bank', 'Child One Save Bank'], 'Child One Checking']], memoPrefix, memoSuffix),
                childTarget('child-two', 'Child Two Budget', 'YNAB_CHILD_TWO_TOKEN', [['spend', ['Child Two Spend Bank'], 'Child Two Checking']])
            ],
            300,
            new SyncLoggingConfig(tempDir.resolve('parent-child-sync.log').toString(), 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 45, 45)
        )
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
            },
            Clock.fixed(Instant.parse('2026-08-01T12:00:00Z'), ZoneOffset.UTC)
        )
    }

    private static ChildBudgetSyncTarget childTarget(
        String childKey,
        String budgetName,
        String tokenEnvVarName,
        List mappingRows,
        String memoPrefix = 'YBOD: ',
        String memoSuffix = ''
    ) {
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
            },
            memoPrefix: memoPrefix,
            memoSuffix: memoSuffix
        )
    }

    private SyncConfig autoCreateSyncConfig(boolean autoCreate = true, Boolean onBudget = true,
                                            String stripRegex = ' Bank$') {
        String dbPath = tempDir.resolve('syncstate-wiremock.db').toString()
        new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                new ChildBudgetSyncTarget(
                    childKey: 'child-one',
                    budgetName: 'Child One Budget',
                    tokenEnvVarName: 'YNAB_CHILD_ONE_TOKEN',
                    accountMappings: [
                        new ChildAccountMapping('spend-save',
                            [new ParentCategoryNameMatcher('Child One Spend Bank', false),
                             new ParentCategoryNameMatcher('Child One Save Bank', false)],
                            'Child One Checking')
                    ],
                    memoPrefix: 'YBOD: ',
                    memoSuffix: '',
                    autoCreateAccounts: autoCreate,
                    createdAccountOnBudget: onBudget,
                    accountCreationNameStripRegex: stripRegex
                )
            ],
            300,
            new SyncLoggingConfig(tempDir.resolve('parent-child-sync.log').toString(), 'INFO', 7, 10),
            new SyncStateConfig(dbPath, 45, 45)
        )
    }

    private void stubCreateAccount(String budgetId, String name, String accountId,
                                   String type = 'checking', boolean onBudget = true) {
        stubFor(post(urlEqualTo("/v1/plans/${budgetId}/accounts"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader('Content-Type', 'application/json')
                .withBody(JsonOutput.toJson([data: [account: [
                    id: accountId, name: name, type: type, on_budget: onBudget, balance: 0
                ]]]))))
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

    private SyncConfig splitRoutingIsolationConfig() {
        new SyncConfig(
            new BudgetRef('Parent Budget', 'YNAB_PARENT_TOKEN'),
            [
                childTarget('child-one', 'Child One Budget', 'YNAB_CHILD_ONE_TOKEN', [
                    ['spend', ['Child One Spend Bank'], 'Child One Spend Account'],
                    ['save', ['Child One Save Bank'], 'Child One Save Account']
                ]),
                childTarget('child-two', 'Child Two Budget', 'YNAB_CHILD_TWO_TOKEN', [
                    ['spend', ['Child Two Spend Bank'], 'Child Two Checking']
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
            data: [plans: [
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
            .inScenario('parent-transaction-deltas')
        if (lastKnowledge == null) {
            mapping.withQueryParam('since_date', matching('\\d{4}-\\d{2}-\\d{2}'))
            mapping.withQueryParam('last_knowledge_of_server', absent())
            mapping.whenScenarioStateIs('Started')
        } else {
            mapping.withQueryParam('since_date', absent())
            mapping.withQueryParam('last_knowledge_of_server', equalTo(lastKnowledge.toString()))
            mapping.whenScenarioStateIs("knowledge-${lastKnowledge}")
        }
        mapping.willReturn(jsonResponse([data: [server_knowledge: serverKnowledge, transactions: transactions]]))
            .willSetStateTo("knowledge-${serverKnowledge}")
        stubFor(mapping)
        def retryMapping = get(urlPathEqualTo('/v1/plans/parent-budget-id/transactions'))
            .inScenario('parent-transaction-deltas')
            .whenScenarioStateIs("knowledge-${serverKnowledge}")
        if (lastKnowledge == null) {
            retryMapping.withQueryParam('since_date', matching('\\d{4}-\\d{2}-\\d{2}'))
            retryMapping.withQueryParam('last_knowledge_of_server', absent())
        } else {
            retryMapping.withQueryParam('since_date', absent())
            retryMapping.withQueryParam('last_knowledge_of_server', equalTo(lastKnowledge.toString()))
        }
        stubFor(retryMapping.willReturn(
            jsonResponse([data: [server_knowledge: serverKnowledge, transactions: transactions]])))
        transactions.each { Map transaction ->
            stubFor(get(urlEqualTo("/v1/plans/parent-budget-id/transactions/${transaction.id}"))
                .inScenario('parent-transaction-deltas')
                .whenScenarioStateIs("knowledge-${serverKnowledge}")
                .willReturn(jsonResponse([data: [server_knowledge: serverKnowledge, transaction: transaction]])))
        }
    }

    private void stubMoneyMovements(List<Map> movements) {
        stubFor(get(urlEqualTo('/v1/plans/parent-budget-id/money_movements'))
            .willReturn(jsonResponse([data: [server_knowledge: 1, money_movements: movements]])))
    }

    private void stubMoneyMovementsInOrder(List<Map> first, List<Map> second) {
        String path = '/v1/plans/parent-budget-id/money_movements'
        stubFor(get(urlEqualTo(path)).inScenario('movement-routing-read').whenScenarioStateIs('Started')
            .willReturn(jsonResponse([data: [server_knowledge: 1, money_movements: first]]))
            .willSetStateTo('second-snapshot'))
        stubFor(get(urlEqualTo(path)).inScenario('movement-routing-read').whenScenarioStateIs('second-snapshot')
            .willReturn(jsonResponse([data: [server_knowledge: 2, money_movements: second]])))
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

    private void stubChildPostsInOrder(String budgetId, List<String> transactionIds) {
        String scenario = "ordered-posts-${budgetId}-${transactionIds.join('-')}"
        transactionIds.eachWithIndex { String transactionId, int index ->
            String state = index == 0 ? 'Started' : "posted-${index}"
            def mapping = post(urlEqualTo("/v1/plans/${budgetId}/transactions/bulk"))
                .inScenario(scenario).whenScenarioStateIs(state)
                .willReturn(jsonResponse([data: [bulk: [transaction_ids: [transactionId]]]]))
            if (index + 1 < transactionIds.size()) {
                mapping.willSetStateTo("posted-${index + 1}")
            }
            stubFor(mapping)
        }
    }

    private void stubChildPostSuccessThenFailure(String budgetId, String transactionId) {
        String path = "/v1/plans/${budgetId}/transactions/bulk"
        stubFor(post(urlEqualTo(path)).inScenario('movement-independent').whenScenarioStateIs('Started')
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: [transactionId]]]]))
            .willSetStateTo('movement-fails'))
        stubFor(post(urlEqualTo(path)).inScenario('movement-independent').whenScenarioStateIs('movement-fails')
            .willReturn(errorResponse(500, 'movement mutation failure')))
    }

    private void stubChildDelete(String budgetId, String transactionId) {
        stubFor(delete(urlEqualTo("/v1/plans/${budgetId}/transactions/${transactionId}"))
            .willReturn(jsonResponse([data: [server_knowledge: 1, transaction: [
                id: transactionId, account_id: 'child-one-account-id', date: '2026-07-01', amount: -1000,
                cleared: 'cleared', approved: false, deleted: true
            ]]])))
    }

    private void stubChildLookup(String budgetId, String transactionId, String accountId,
                                 String date, int amount, String payeeId, String payeeName) {
        stubFor(get(urlEqualTo("/v1/plans/${budgetId}/transactions/${transactionId}"))
            .willReturn(jsonResponse([data: [server_knowledge: 1, transaction: [
                id: transactionId, account_id: accountId, date: date, amount: amount,
                payee_id: payeeId, payee_name: payeeName, category_id: null, memo: 'child memo',
                cleared: 'cleared', approved: false, deleted: false
            ]]])))
    }

    private void stubChildMissing(String budgetId, String transactionId) {
        stubFor(get(urlEqualTo("/v1/plans/${budgetId}/transactions/${transactionId}"))
            .willReturn(errorResponse(404, 'transaction not found')))
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

    private void stubChildPostSuccessFailureSuccessSuccess(String budgetId) {
        String path = "/v1/plans/${budgetId}/transactions/bulk"
        stubFor(post(urlEqualTo(path))
            .inScenario('three-cycle-posts')
            .whenScenarioStateIs('Started')
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: ['created-first']]]]))
            .willSetStateTo('second-fails'))
        stubFor(post(urlEqualTo(path))
            .inScenario('three-cycle-posts')
            .whenScenarioStateIs('second-fails')
            .willReturn(errorResponse(500, 'simulated second post failure'))
            .willSetStateTo('retry-succeeds'))
        stubFor(post(urlEqualTo(path))
            .inScenario('three-cycle-posts')
            .whenScenarioStateIs('retry-succeeds')
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: ['created-retry']]]]))
            .willSetStateTo('new-work-succeeds'))
        stubFor(post(urlEqualTo(path))
            .inScenario('three-cycle-posts')
            .whenScenarioStateIs('new-work-succeeds')
            .willReturn(jsonResponse([data: [bulk: [transaction_ids: ['created-new']]]])))
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

    private void stubChildAccountsSuccessFailureSuccess(String budgetId, List<Map> firstAccounts,
                                                         List<Map> restoredAccounts) {
        String scenario = "routing-recovery-${budgetId}"
        String path = "/v1/plans/${budgetId}/accounts"
        stubFor(get(urlEqualTo(path)).inScenario(scenario).whenScenarioStateIs('Started')
            .willReturn(jsonResponse([data: [accounts: firstAccounts]]))
            .willSetStateTo('routing-fails'))
        stubFor(get(urlEqualTo(path)).inScenario(scenario).whenScenarioStateIs('routing-fails')
            .willReturn(errorResponse(401, 'unauthorized child token'))
            .willSetStateTo('routing-restored'))
        stubFor(get(urlEqualTo(path)).inScenario(scenario).whenScenarioStateIs('routing-restored')
            .willReturn(jsonResponse([data: [accounts: restoredAccounts]])))
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
            def rs = connection.createStatement().executeQuery('''
                SELECT outcome, failure_reason, returned_child_transaction_id
                FROM operation_attempts ORDER BY id
            ''')
            List rows = []
            while (rs.next()) {
                rows << [
                    status: rs.getString('outcome') == 'failed' ? 'failed' : 'applied',
                    failure_reason: rs.getString('failure_reason'),
                    created_child_transaction_id: rs.getString('returned_child_transaction_id')
                ]
            }
            rows
        }
    }

    private List<Map> runRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('SELECT status, error_summary FROM sync_runs ORDER BY id')
            List rows = []
            while (rs.next()) {
                rows << [status: rs.getString('status'), error_summary: rs.getString('error_summary')]
            }
            rows
        }
    }

    private List<Map> mirrorRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery(
                'SELECT child_transaction_id, status FROM child_mirrors ORDER BY id')
            List rows = []
            while (rs.next()) rows << [child_transaction_id: rs.getString(1), status: rs.getString(2)]
            rows
        }
    }

    private List<Map> operationRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('''
                SELECT operation.status, batch.source_kind
                FROM sync_operations operation
                LEFT JOIN ingestion_batches batch ON batch.id = operation.ingestion_batch_id
                ORDER BY operation.id
            ''')
            List rows = []
            while (rs.next()) rows << [status: rs.getString(1), source_kind: rs.getString(2)]
            rows
        }
    }

    private List<Map> sourceLifecycleRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery(
                'SELECT parent_transaction_id, lifecycle_status FROM source_entities ORDER BY id')
            List rows = []
            while (rs.next()) {
                rows << [parent_transaction_id: rs.getString(1), lifecycle_status: rs.getString(2)]
            }
            rows
        }
    }

    private List<Map> ingestionBatchRows() {
        withDb { connection ->
            def rs = connection.createStatement().executeQuery('''
                SELECT source_kind, ynab_server_knowledge, status FROM ingestion_batches ORDER BY id
            ''')
            List rows = []
            while (rs.next()) rows << [source_kind: rs.getString(1), server_knowledge: rs.getObject(2),
                                       status: rs.getString(3)]
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
