import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Thin, repo-local wrapper over JDK HttpClient for the YNAB API.
 *
 * Why this exists:
 * - replaces archived/incompatible http-builder-ng without adding another client dependency
 * - keeps call sites in RecordAllowance easy to read
 * - centralizes auth headers, JSON serialization, and error handling in one obvious place
 *
 * Deliberately not a generic DSL. It only implements the small JSON GET/POST surface this
 * script actually needs.
 */
@Slf4j
class YnabHttpClient {
    final String baseUrl
    final String accessToken
    final HttpClient httpClient
    final JsonSlurper jsonSlurper = new JsonSlurper()
    final Duration requestTimeout

    YnabHttpClient(String baseUrl, String accessToken) {
        this(baseUrl, accessToken, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build())
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient) {
        this(baseUrl, accessToken, httpClient, Duration.ofSeconds(30))
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout) {
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
        this.accessToken = accessToken
        this.httpClient = httpClient
        this.requestTimeout = requestTimeout
    }

    def getJson(String path) {
        HttpRequest request = baseRequest(path)
            .GET()
            .build()
        return sendJson(request, 'GET', path)
    }

    def postJson(String path, Object payload) {
        String json = JsonOutput.toJson(payload)
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return sendJson(request, 'POST', path)
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(resolve(path))
            .timeout(requestTimeout)
            .header('Authorization', "Bearer ${accessToken}")
            .header('Accept', 'application/json')
    }

    private URI resolve(String path) {
        String normalized = path.startsWith('/') ? path : "/${path}"
        return URI.create(baseUrl + normalized)
    }

    private def sendJson(HttpRequest request, String method, String path) {
        HttpResponse<String> response
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException("YNAB ${method} ${path} interrupted", e)
        } catch (HttpTimeoutException | TimeoutException e) {
            throw new IllegalStateException("YNAB ${method} ${path} timed out after ${requestTimeout}", e)
        }
        String bodyText = response.body()

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("YNAB ${method} ${path} failed with status ${response.statusCode()}: ${bodyText}")
        }

        if (bodyText == null || bodyText.isBlank()) {
            return null
        }

        return jsonSlurper.parseText(bodyText)
    }
}
