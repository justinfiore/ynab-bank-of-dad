import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import groovy.json.JsonSlurper
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.slf4j.LoggerFactory
import spock.lang.Specification

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class YnabHttpClientSpec extends Specification {

    MockWebServer server

    def setup() {
        server = new MockWebServer()
        server.start()
    }

    def cleanup() {
        server.shutdown()
    }

    def "getJson sends auth header and parses response body"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"plans":[{"id":"budget-1"}]}}'))
        def client = buildClient()

        when:
        def response = client.getJson('/v1/plans')
        def request = server.takeRequest()

        then:
        response.data.plans[0].id == 'budget-1'
        request.method == 'GET'
        request.path == '/v1/plans'
        request.getHeader('Authorization') == 'Bearer token'
        request.getHeader('Accept') == 'application/json'
    }

    def "postJson sends JSON body and parses response"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"bulk":{"transaction_ids":["txn-1"]}}}'))
        def client = buildClient()
        def payload = [transactions: [[payee_name: 'Test Transaction', amount: 1000]]]

        when:
        def response = client.postJson('/v1/plans/budget-1/transactions/bulk', payload)
        def request = server.takeRequest()
        def body = new JsonSlurper().parseText(request.body.readUtf8())

        then:
        response.data.bulk.transaction_ids == ['txn-1']
        request.method == 'POST'
        request.path == '/v1/plans/budget-1/transactions/bulk'
        request.getHeader('Authorization') == 'Bearer token'
        request.getHeader('Content-Type').startsWith('application/json')
        body == payload
    }

    def "postJsonWithMetadata preserves status body text and parsed body"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(201)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"bulk":{"transaction_ids":["txn-1"]}}}'))
        def client = buildClient()
        def payload = [transactions: [[payee_name: 'Test Transaction', amount: 1000]]]

        when:
        def response = client.postJsonWithMetadata('/v1/plans/budget-1/transactions/bulk', payload)
        def request = server.takeRequest()

        then:
        response.statusCode == 201
        response.bodyText == '{"data":{"bulk":{"transaction_ids":["txn-1"]}}}'
        response.body.data.bulk.transaction_ids == ['txn-1']
        request.method == 'POST'
        request.path == '/v1/plans/budget-1/transactions/bulk'
    }

    def "putJsonWithMetadata sends JSON and deleteJsonWithMetadata accepts configured status"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{"transaction":{"id":"txn-1"}}}'))
        server.enqueue(new MockResponse()
            .setResponseCode(404)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"error":{"detail":"not found"}}'))
        def client = buildClient()
        def payload = [transaction: [amount: -1000, approved: false]]

        when:
        def putResponse = client.putJsonWithMetadata('/v1/plans/budget-1/transactions/txn-1', payload)
        def putRequest = server.takeRequest()
        def deleteResponse = client.deleteJsonWithMetadata('/v1/plans/budget-1/transactions/missing', [404] as Set)
        def deleteRequest = server.takeRequest()

        then:
        putResponse.statusCode == 200
        putRequest.method == 'PUT'
        new JsonSlurper().parseText(putRequest.body.readUtf8()) == payload
        putRequest.getHeader('Authorization') == 'Bearer token'
        deleteResponse.statusCode == 404
        deleteRequest.method == 'DELETE'
        deleteRequest.getHeader('Authorization') == 'Bearer token'
    }

    def "malformed JSON success includes HTTP operation context"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{not-json'))

        when:
        buildClient().putJsonWithMetadata('/v1/plans/budget-1/transactions/txn-1', [transaction: [amount: -1]])

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB PUT transactions returned invalid JSON'
    }

    def "successful blank response body returns null"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(204))
        def client = buildClient()

        when:
        def response = client.postJson('/v1/plans/budget-1/transactions/bulk', [transactions: []])

        then:
        response == null
    }

    def "successful whitespace-only response body returns null"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('   \n\t  '))
        def client = buildClient()

        when:
        def response = client.getJson('/v1/plans')

        then:
        response == null
    }

    def "write telemetry records sanitized non-GET attempts before send and excludes GET"() {
        given:
        Path telemetry = Files.createTempFile('ynab-write-attempts-', '.jsonl')
        def recordingClient = new RecordingHttpClient(telemetry)
        def client = new YnabHttpClient('https://api.ynab.com', 'access-token-secret',
            recordingClient, Duration.ofSeconds(7), 0, { Duration ignored -> },
            { Instant.EPOCH }, telemetry)

        when:
        client.getJson('/v1/plans/private-plan-id')
        client.postJson('/v1/plans/private-plan-id/transactions/private-transaction-id',
            [token: 'payload-secret'])

        then:
        recordingClient.telemetryWasPresentAtSend == [false, true]
        def records = Files.readAllLines(telemetry).collect { new JsonSlurper().parseText(it) }
        records == [[method: 'POST', resource_class: 'transactions']]
        String telemetryText = Files.readString(telemetry)
        !['private-plan-id', 'private-transaction-id', 'access-token-secret',
          'payload-secret', 'api.ynab.com'].any { telemetryText.contains(it) }

        cleanup:
        Files.deleteIfExists(telemetry)
    }

    def "write telemetry failure prevents the HTTP send"() {
        given:
        Path telemetryDirectory = Files.createTempDirectory('ynab-write-attempt-directory-')
        def recordingClient = new RecordingHttpClient(telemetryDirectory)
        def client = new YnabHttpClient('https://api.ynab.com', 'token', recordingClient,
            Duration.ofSeconds(7), 0, { Duration ignored -> }, { Instant.EPOCH },
            telemetryDirectory)

        when:
        client.deleteJsonWithMetadata('/v1/plans/private-plan-id/transactions/private-id')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB DELETE transactions could not record write attempt'
        recordingClient.sendCount == 0
        !ex.message.contains(telemetryDirectory.toString())
        !ex.message.contains('private-plan-id')
        !ex.message.contains('private-id')

        cleanup:
        Files.deleteIfExists(telemetryDirectory)
    }

    def "all HTTP methods retry explicit 429 rejection with the same request"() {
        given:
        Path telemetry = Files.createTempFile('ynab-retry-write-attempts-', '.jsonl')
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('Retry-After', '3')
            .setBody('rejected request'))
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 'token',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { Instant.EPOCH }, telemetry)

        when:
        issueRequest(client, method)
        def first = server.takeRequest()
        def second = server.takeRequest()

        then:
        first.method == method
        second.method == method
        first.path == second.path
        first.body.readUtf8() == second.body.readUtf8()
        waits == [Duration.ofSeconds(3)]
        Files.readAllLines(telemetry).size() == expectedTelemetryRecords
        client.rateLimitRetryCount == 1
        client.otherRetryCount == 0

        cleanup:
        Files.deleteIfExists(telemetry)

        where:
        method   | expectedTelemetryRecords
        'GET'    | 0
        'POST'   | 2
        'PUT'    | 2
        'PATCH'  | 2
        'DELETE' | 2
    }

    def "retry delay honors case-insensitive numeric date and reset headers"() {
        given:
        Instant now = Instant.parse('2026-08-30T12:00:00Z')
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('rEtRy-AfTeR', '3'))
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('Retry-After', DateTimeFormatter.RFC_1123_DATE_TIME.format(
                now.plusSeconds(20).atZone(ZoneOffset.UTC))))
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('x-RaTe-LiMiT-ReSeT', now.plusSeconds(30).epochSecond.toString()))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 'token',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { now })

        when:
        client.postJson('/v1/plans/private-plan/transactions', [secret: 'payload'])

        then:
        waits == [Duration.ofSeconds(3), Duration.ofSeconds(20), Duration.ofSeconds(30)]
    }

    def "retry delay uses capped linear fallback and caps a resume header at one hour"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(429).setHeader('Retry-After', 'invalid'))
        server.enqueue(new MockResponse().setResponseCode(429).setHeader('Retry-After', '9999'))
        server.enqueue(new MockResponse().setResponseCode(429))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 'token',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { Instant.EPOCH })

        when:
        client.getJson('/v1/plans')

        then:
        waits == [Duration.ofSeconds(5), Duration.ofSeconds(3600), Duration.ofSeconds(15)]
    }

    def "out of Instant range reset epoch falls through to linear retry"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('RateLimit-Reset', Long.MAX_VALUE.toString()))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 'token',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { Instant.EPOCH })

        when:
        def response = client.getJson('/v1/plans')

        then:
        response == [data: [:]]
        waits == [Duration.ofSeconds(5)]
        server.requestCount == 2
    }

    def "finite retry exhaustion and non-429 errors fail immediately without leakage"() {
        given:
        String privatePath = '/v1/plans/private-plan-id/transactions/private-transaction-id'
        String secretBody = '{"token":"body-secret","url":"https://secret.example/path"}'
        int status = initialStatus
        server.enqueue(new MockResponse()
            .setResponseCode(status).setHeader('Authorization', 'Bearer header-secret')
            .setBody(secretBody))
        if (status == 429) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody(secretBody))
        }
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 'access-token-secret',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), 1,
            { Duration delay -> waits << delay }, { Instant.EPOCH })

        when:
        client.postJson(privatePath, [token: 'payload-secret'])

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "YNAB POST transactions failed with status ${status}"
        !['private-plan-id', 'private-transaction-id', 'body-secret', 'header-secret',
          'access-token-secret', 'payload-secret', 'secret.example'].any { ex.message.contains(it) }
        server.requestCount == expectedRequests
        waits.size() == expectedWaits

        where:
        initialStatus | expectedRequests | expectedWaits
        429           | 2                | 1
        400           | 1                | 0
        401           | 1                | 0
        403           | 1                | 0
    }

    def "parseAccessTokens splits CSV, trims, drops empties, and de-duplicates"() {
        expect:
        YnabHttpClient.parseAccessTokens(raw) == expected

        where:
        raw                  | expected
        'token'              | ['token']
        'token-a, token-b'   | ['token-a', 'token-b']
        't1,t1, t2,, t1'     | ['t1', 't2']
        ', ,'                | []
        null                 | []
        '  '                 | []
    }

    def "blank CSV constructor fails without leaking a token value"() {
        when:
        new YnabHttpClient(server.url('/').toString(), ', ,')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'access tokens must not be empty'
    }

    def "documented 429 body without remaining fields logs absence then retries"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setBody('{"error":{"id":"429","name":"too_many_requests","detail":"Too many requests"}}'))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        List<String> infos = []
        def client = rateLimitClient('token', waits, infos)

        when:
        client.getJson('/v1/plans')

        then:
        waits == [Duration.ofSeconds(5)]
        infos.size() == 1
        infos[0].contains('YNAB GET plans rate limited on token 1 of 1')
        infos[0].contains('no remaining/reset metadata')
        !infos[0].contains('token-secret')
        !infos[0].contains('too_many_requests')
    }

    def "DEBUG log includes complete 429 response headers and body"() {
        given:
        String responseBody = '{"error":{"id":"429","detail":"first line\\nsecond line","extra":{"value":42}}}'
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .addHeader('X-Debug-One', 'value-a')
            .addHeader('X-Debug-Two', 'value-b-1')
            .addHeader('X-Debug-Two', 'value-b-2')
            .setBody(responseBody))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        Logger logger = LoggerFactory.getLogger(YnabHttpClient) as Logger
        Level previousLevel = logger.level
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        def client = rateLimitClient('access-token-secret', [])
        client.registerBudgetName('budget-1', 'Fiores')

        when:
        client.getJson('/v1/plans/budget-1/transactions')

        then:
        List<ILoggingEvent> debugEvents = appender.list.findAll { it.level == Level.DEBUG }
        debugEvents.size() == 1
        String message = debugEvents[0].formattedMessage
        message.startsWith("YNAB GET transactions for budget 'Fiores' received 429 response; headers=")
        String lowerMessage = message.toLowerCase()
        lowerMessage.contains('x-debug-one:[value-a]')
        lowerMessage.contains('x-debug-two:[value-b-1, value-b-2]')
        message.contains("; body=${responseBody}")
        !message.contains('access-token-secret')
        !message.contains('budget-1')

        cleanup:
        logger.detachAppender(appender)
        logger.level = previousLevel
        appender.stop()
    }

    def "INFO log includes Retry-After delay without Authorization values"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(429)
            .setHeader('Retry-After', '3')
            .setHeader('Authorization', 'Bearer header-secret'))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        List<String> infos = []
        def client = rateLimitClient('access-token-secret', waits, infos)

        when:
        client.getJson('/v1/plans')

        then:
        waits == [Duration.ofSeconds(3)]
        infos[0].contains('retry after 3S')
        !infos[0].contains('PT3S')
        infos[0].contains('headers=Retry-After')
        !infos[0].contains('access-token-secret')
        !infos[0].contains('header-secret')
        !infos[0].contains('Authorization: Bearer')
    }

    def "rate limit logs identify budget name token slots hourly call counts and readable delays"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(429).setHeader('Retry-After', '3600'))
        server.enqueue(new MockResponse().setResponseCode(429).setHeader('Retry-After', '3600'))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        List<String> infos = []
        List<String> warnings = []
        def client = new YnabHttpClient(server.url('/').toString(), 't1,t2',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { Instant.EPOCH }, null,
            { String msg -> infos << msg }, { String msg -> warnings << msg })
        client.registerBudgetName('budget-1', 'Fiores')

        when:
        client.getJson('/v1/plans/budget-1/transactions')

        then:
        waits == [Duration.ofHours(1)]
        infos == [
            "YNAB GET transactions for budget 'Fiores' rate limited on token 1 of 2; API calls in last hour by token: token 1=1, token 2=0; retry after 1H; headers=Retry-After",
            "YNAB GET transactions for budget 'Fiores' rate limited on token 2 of 2; API calls in last hour by token: token 1=1, token 2=1; retry after 1H; headers=Retry-After",
        ]
        warnings == [
            "YNAB GET transactions for budget 'Fiores' was rate limited on tokens 1, 2 of 2; " +
                'API calls in last hour by token: token 1=1, token 2=1; retrying after 1H'
        ]
        !((infos + warnings).join('\n').contains('PT1H'))
        !((infos + warnings).join('\n').contains('budget-1'))
        !((infos + warnings).join('\n').contains('t1'))
        !((infos + warnings).join('\n').contains('t2'))
    }

    def "hourly API call count excludes attempts older than one hour"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        server.enqueue(new MockResponse().setResponseCode(429))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        Instant now = Instant.EPOCH
        List<String> infos = []
        def client = new YnabHttpClient(server.url('/').toString(), 't1,t2',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration ignored -> }, { now }, null, { String msg -> infos << msg })
        client.registerBudgetName('budget-1', 'Fiores')
        client.getJson('/v1/plans/budget-1/transactions')

        when:
        now = now.plusSeconds(3601)
        client.getJson('/v1/plans/budget-1/transactions')

        then:
        infos.size() == 1
        infos[0].contains('API calls in last hour by token: token 1=1, token 2=0')
    }

    def "second token succeeds without sleep and stays sticky"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(429).setBody('{"error":{"id":"429"}}'))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{"ok":1}}'))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{"ok":2}}'))
        List<Duration> waits = []
        List<String> infos = []
        def client = rateLimitClient('t1, t2', waits, infos)

        when:
        def first = client.getJson('/v1/plans')
        def firstAuth = server.takeRequest().getHeader('Authorization')
        def secondAuth = server.takeRequest().getHeader('Authorization')
        def second = client.getJson('/v1/plans')
        def thirdAuth = server.takeRequest().getHeader('Authorization')

        then:
        first.data.ok == 1
        second.data.ok == 2
        waits.isEmpty()
        firstAuth == 'Bearer t1'
        secondAuth == 'Bearer t2'
        thirdAuth == 'Bearer t2'
        infos[0].contains('token 1 of 2')
        !infos[0].contains('t1')
    }

    def "token rotation does not count toward maxRateLimitRetries"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(429))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = new YnabHttpClient(server.url('/').toString(), 't1,t2',
            HttpClient.newHttpClient(), Duration.ofSeconds(7), 0,
            { Duration delay -> waits << delay }, { Instant.EPOCH })

        when:
        client.getJson('/v1/plans')

        then:
        waits.isEmpty()
        server.requestCount == 2
    }

    def "all tokens 429 then linear backoff retries from first token"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(429))
        server.enqueue(new MockResponse().setResponseCode(429))
        server.enqueue(new MockResponse().setResponseCode(200).setBody('{"data":{}}'))
        List<Duration> waits = []
        def client = rateLimitClient('t1,t2', waits)

        when:
        client.postJson('/v1/plans/private-plan/transactions', [request: 'same'])
        def auths = (1..3).collect { server.takeRequest().getHeader('Authorization') }

        then:
        waits == [Duration.ofSeconds(5)]
        auths == ['Bearer t1', 'Bearer t2', 'Bearer t1']
    }

    def "non-429 4xx does not rotate to the next token"() {
        given:
        server.enqueue(new MockResponse().setResponseCode(401).setBody('{"error":{"id":"401"}}'))
        List<Duration> waits = []
        def client = rateLimitClient('t1,t2', waits)

        when:
        client.getJson('/v1/plans')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB GET plans failed with status 401'
        waits.isEmpty()
        server.requestCount == 1
    }

    def "timeouts surface a clear error with configured duration"() {
        given:
        def timeout = Duration.ofSeconds(7)
        def client = new YnabHttpClient('https://api.ynab.com', 'token', new TimeoutThrowingHttpClient(), timeout)

        when:
        client.getJson('/v1/plans')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB GET plans timed out'
        ex.cause instanceof HttpTimeoutException
    }

    def "interruption preserves thread interrupt status and surfaces clear error"() {
        given:
        def client = new YnabHttpClient('https://api.ynab.com', 'token', new InterruptingHttpClient())

        when:
        client.getJson('/v1/plans')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB GET plans interrupted'
        ex.cause instanceof InterruptedException
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted()
    }

    def "new HTTP verbs preserve timeout and interruption behavior"() {
        given:
        def timeout = Duration.ofSeconds(7)
        def httpClient = failure == 'timeout' ? new TimeoutThrowingHttpClient() : new InterruptingHttpClient()
        def client = new YnabHttpClient('https://api.ynab.com', 'token', httpClient, timeout)

        when:
        if (method == 'PUT') {
            client.putJsonWithMetadata('/v1/plans/budget-1/transactions/txn-1', [transaction: [amount: -1]])
        } else {
            client.deleteJsonWithMetadata('/v1/plans/budget-1/transactions/txn-1')
        }

        then:
        def ex = thrown(IllegalStateException)
        ex.message == expectedMessage
        ex.cause.class == expectedCause
        Thread.currentThread().isInterrupted() == interrupted

        cleanup:
        Thread.interrupted()

        where:
        method   | failure     | expectedCause          | interrupted | expectedMessage
        'PUT'    | 'timeout'   | HttpTimeoutException   | false       | 'YNAB PUT transactions timed out'
        'DELETE' | 'interrupt' | InterruptedException   | true        | 'YNAB DELETE transactions interrupted'
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient(server.url('/').toString(), 'token')
    }

    private YnabHttpClient rateLimitClient(String tokens, List waits, List infos = null) {
        new YnabHttpClient(server.url('/').toString(), tokens,
            HttpClient.newHttpClient(), Duration.ofSeconds(7), null,
            { Duration delay -> waits << delay }, { Instant.EPOCH }, null,
            infos == null ? null : { String msg -> infos << msg })
    }

    private static void issueRequest(YnabHttpClient client, String method) {
        String path = '/v1/plans/private-plan/transactions/private-transaction'
        switch (method) {
            case 'GET': client.getJson(path); break
            case 'POST': client.postJson(path, [request: 'same']); break
            case 'PUT': client.putJsonWithMetadata(path, [request: 'same']); break
            case 'PATCH': client.patchJsonWithMetadata(path, [request: 'same']); break
            case 'DELETE': client.deleteJsonWithMetadata(path); break
            default: throw new AssertionError(method)
        }
    }

    private static class TimeoutThrowingHttpClient extends HttpClient {
        @Override
        Optional<CookieHandler> cookieHandler() {
            Optional.empty()
        }

        @Override
        Optional<Duration> connectTimeout() {
            Optional.of(Duration.ofSeconds(1))
        }

        @Override
        Redirect followRedirects() {
            Redirect.NEVER
        }

        @Override
        Optional<ProxySelector> proxy() {
            Optional.empty()
        }

        @Override
        SSLContext sslContext() {
            null
        }

        @Override
        SSLParameters sslParameters() {
            null
        }

        @Override
        Optional<Authenticator> authenticator() {
            Optional.empty()
        }

        @Override
        Version version() {
            Version.HTTP_1_1
        }

        @Override
        Optional<Executor> executor() {
            Optional.empty()
        }

        @Override
        <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new HttpTimeoutException('timed out')
        }

        @Override
        <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException('sendAsync not used in tests')
        }

        @Override
        <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException('sendAsync not used in tests')
        }
    }

    private static class InterruptingHttpClient extends TimeoutThrowingHttpClient {
        @Override
        <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new InterruptedException('interrupted for test')
        }
    }

    private static class RecordingHttpClient extends TimeoutThrowingHttpClient {
        int sendCount = 0
        List<Boolean> telemetryWasPresentAtSend = []
        final Path telemetryPath

        RecordingHttpClient(Path telemetryPath) {
            this.telemetryPath = telemetryPath
        }

        @Override
        <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            sendCount++
            telemetryWasPresentAtSend << (Files.isRegularFile(telemetryPath) && Files.size(telemetryPath) > 0)
            new StubHttpResponse<T>(200, (T) '{"data":{}}')
        }
    }

    private static class StubHttpResponse<T> implements HttpResponse<T> {
        final int status
        final T responseBody

        StubHttpResponse(int status, T responseBody) {
            this.status = status
            this.responseBody = responseBody
        }

        @Override int statusCode() { status }
        @Override HttpRequest request() { null }
        @Override Optional<HttpResponse<T>> previousResponse() { Optional.empty() }
        @Override java.net.http.HttpHeaders headers() {
            java.net.http.HttpHeaders.of([:], { String ignoredA, String ignoredB -> true })
        }
        @Override T body() { responseBody }
        @Override Optional<javax.net.ssl.SSLSession> sslSession() { Optional.empty() }
        @Override URI uri() { URI.create('https://example.invalid') }
        @Override HttpClient.Version version() { HttpClient.Version.HTTP_1_1 }
    }
}
