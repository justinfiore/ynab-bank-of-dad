import ynabbankofdad.allowance.*
import ynabbankofdad.config.*
import ynabbankofdad.model.*
import ynabbankofdad.ynab.*
import ynabbankofdad.sync.*
import ynabbankofdad.sync.model.*
import ynabbankofdad.sync.state.*
import groovy.json.JsonSlurper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
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
            .setBody('{"data":{"budgets":[{"id":"budget-1"}]}}'))
        def client = buildClient()

        when:
        def response = client.getJson('/v1/plans')
        def request = server.takeRequest()

        then:
        response.data.budgets[0].id == 'budget-1'
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

    def "non-2xx responses surface a clear error"() {
        given:
        server.enqueue(new MockResponse()
            .setResponseCode(500)
            .setHeader('Content-Type', 'application/json')
            .setBody('{"error":{"detail":"boom"}}'))
        def client = buildClient()

        when:
        client.postJson('/v1/plans/budget-1/transactions/bulk', [transactions: []])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('YNAB POST /v1/plans/budget-1/transactions/bulk failed with status 500')
        ex.message.contains('boom')
    }

    def "timeouts surface a clear error with configured duration"() {
        given:
        def timeout = Duration.ofSeconds(7)
        def client = new YnabHttpClient('https://api.youneedabudget.com', 'token', new TimeoutThrowingHttpClient(), timeout)

        when:
        client.getJson('/v1/plans')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB GET /v1/plans timed out after PT7S'
        ex.cause instanceof HttpTimeoutException
    }

    def "interruption preserves thread interrupt status and surfaces clear error"() {
        given:
        def client = new YnabHttpClient('https://api.youneedabudget.com', 'token', new InterruptingHttpClient())

        when:
        client.getJson('/v1/plans')

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'YNAB GET /v1/plans interrupted'
        ex.cause instanceof InterruptedException
        Thread.currentThread().isInterrupted()

        cleanup:
        Thread.interrupted()
    }

    private YnabHttpClient buildClient() {
        new YnabHttpClient(server.url('/').toString(), 'token')
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
}
