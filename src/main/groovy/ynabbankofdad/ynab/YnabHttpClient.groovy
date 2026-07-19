package ynabbankofdad.ynab

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
 * Deliberately not a generic DSL. It only implements the small JSON HTTP surface this
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
        return sendJson(request, 'GET', path, [] as Set).body
    }

    YnabHttpResponse getJsonWithMetadata(String path, Set<Integer> acceptedStatuses = [] as Set) {
        HttpRequest request = baseRequest(path)
            .GET()
            .build()
        sendJson(request, 'GET', path, acceptedStatuses)
    }

    def postJson(String path, Object payload) {
        String json = JsonOutput.toJson(payload)
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return sendJson(request, 'POST', path, [] as Set).body
    }

    YnabHttpResponse postJsonWithMetadata(String path, Object payload) {
        String json = JsonOutput.toJson(payload)
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return sendJson(request, 'POST', path, [] as Set)
    }

    YnabHttpResponse putJsonWithMetadata(String path, Object payload, Set<Integer> acceptedStatuses = [] as Set) {
        String json = JsonOutput.toJson(payload)
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/json')
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build()
        sendJson(request, 'PUT', path, acceptedStatuses)
    }

    YnabHttpResponse patchJsonWithMetadata(String path, Object payload, Set<Integer> acceptedStatuses = [] as Set) {
        String json = JsonOutput.toJson(payload)
        HttpRequest request = baseRequest(path)
            .header('Content-Type', 'application/json')
            .method('PATCH', HttpRequest.BodyPublishers.ofString(json))
            .build()
        sendJson(request, 'PATCH', path, acceptedStatuses)
    }

    YnabHttpResponse deleteJsonWithMetadata(String path, Set<Integer> acceptedStatuses = [] as Set) {
        HttpRequest request = baseRequest(path)
            .DELETE()
            .build()
        sendJson(request, 'DELETE', path, acceptedStatuses)
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

    private YnabHttpResponse sendJson(HttpRequest request, String method, String path, Set<Integer> acceptedStatuses) {
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

        if ((response.statusCode() < 200 || response.statusCode() >= 300) && !acceptedStatuses.contains(response.statusCode())) {
            throw new IllegalStateException("YNAB ${method} ${path} failed with status ${response.statusCode()}: ${bodyText}")
        }

        def parsedBody
        try {
            parsedBody = (bodyText == null || bodyText.isBlank()) ? null : jsonSlurper.parseText(bodyText)
        } catch (RuntimeException e) {
            throw new IllegalStateException("YNAB ${method} ${path} returned invalid JSON", e)
        }
        return new YnabHttpResponse(response.statusCode(), bodyText, parsedBody)
    }
}

class YnabHttpResponse {
    final int statusCode
    final String bodyText
    final Object body

    YnabHttpResponse(int statusCode, String bodyText, Object body) {
        this.statusCode = statusCode
        this.bodyText = bodyText
        this.body = body
    }
}
