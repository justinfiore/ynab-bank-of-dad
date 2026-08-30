package ynabbankofdad.ynab

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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
    /** Null means retry 429 responses until a non-429 response is received. */
    final Integer maxRateLimitRetries
    final Closure sleeper
    final Closure clock

    YnabHttpClient(String baseUrl, String accessToken) {
        this(baseUrl, accessToken, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build())
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient) {
        this(baseUrl, accessToken, httpClient, Duration.ofSeconds(30))
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout) {
        this(baseUrl, accessToken, httpClient, requestTimeout, null,
            { Duration delay -> Thread.sleep(delay.toMillis()) }, { Instant.now() })
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout,
                   Integer maxRateLimitRetries, Closure sleeper) {
        this(baseUrl, accessToken, httpClient, requestTimeout, maxRateLimitRetries, sleeper,
            { Instant.now() })
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout,
                   Integer maxRateLimitRetries, Closure sleeper, Closure clock) {
        if (maxRateLimitRetries != null && maxRateLimitRetries < 0) {
            throw new IllegalArgumentException('maxRateLimitRetries must be non-negative')
        }
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
        this.accessToken = accessToken
        this.httpClient = httpClient
        this.requestTimeout = requestTimeout
        this.maxRateLimitRetries = maxRateLimitRetries
        this.sleeper = sleeper
        this.clock = clock
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
        int rateLimitRetries = 0
        String resourceClass = resourceClass(path)
        while (true) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new IllegalStateException("YNAB ${method} ${resourceClass} interrupted", e)
            } catch (HttpTimeoutException | TimeoutException e) {
                throw new IllegalStateException("YNAB ${method} ${resourceClass} timed out", e)
            }
            if (response.statusCode() != 429) {
                break
            }
            if (maxRateLimitRetries != null && rateLimitRetries >= maxRateLimitRetries) {
                throw new IllegalStateException("YNAB ${method} ${resourceClass} failed with status 429")
            }
            Duration delay = boundedRetryDelay(response, rateLimitRetries + 1, currentInstant())
            log.warn('YNAB {} {} was rate limited; retrying after {}', method, resourceClass, delay)
            try {
                sleeper.call(delay)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new IllegalStateException("YNAB ${method} ${resourceClass} interrupted while waiting to retry", e)
            }
            rateLimitRetries++
        }
        String bodyText = response.body()

        if ((response.statusCode() < 200 || response.statusCode() >= 300) && !acceptedStatuses.contains(response.statusCode())) {
            throw new IllegalStateException("YNAB ${method} ${resourceClass} failed with status ${response.statusCode()}")
        }

        def parsedBody
        try {
            parsedBody = (bodyText == null || bodyText.isBlank()) ? null : jsonSlurper.parseText(bodyText)
        } catch (RuntimeException e) {
            throw new IllegalStateException("YNAB ${method} ${resourceClass} returned invalid JSON", e)
        }
        return new YnabHttpResponse(response.statusCode(), bodyText, parsedBody)
    }

    private Instant currentInstant() {
        def value = clock.call()
        if (!(value instanceof Instant)) {
            throw new IllegalStateException('YNAB retry clock did not return an Instant')
        }
        (Instant) value
    }

    private static Duration boundedRetryDelay(HttpResponse<String> response, int retryNumber, Instant now) {
        String header = firstHeaderIgnoreCase(response, 'Retry-After')
        try {
            long seconds = Long.parseLong(header)
            if (seconds > 0) {
                return cap(Duration.ofSeconds(seconds))
            }
        } catch (NumberFormatException ignored) {
            try {
                Instant resume = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                if (resume.isAfter(now)) {
                    return cap(Duration.between(now, resume))
                }
            } catch (DateTimeParseException ignoredDate) {
                // Try reset-epoch headers next.
            }
        }
        for (String name : ['RateLimit-Reset', 'X-RateLimit-Reset', 'X-Rate-Limit-Reset']) {
            String reset = firstHeaderIgnoreCase(response, name)
            try {
                Instant resume = Instant.ofEpochSecond(Long.parseLong(reset))
                if (resume.isAfter(now)) {
                    return cap(Duration.between(now, resume))
                }
            } catch (NumberFormatException | DateTimeException ignored) {
                // Continue to the next common reset header.
            }
        }
        return cap(Duration.ofSeconds(5L * retryNumber))
    }

    private static String firstHeaderIgnoreCase(HttpResponse<String> response, String name) {
        def entry = response.headers().map().find { key, ignored -> key.equalsIgnoreCase(name) }
        entry?.value?.find { it != null } ?: ''
    }

    private static Duration cap(Duration delay) {
        Duration maximum = Duration.ofSeconds(3600)
        delay.compareTo(maximum) > 0 ? maximum : delay
    }

    private static String resourceClass(String path) {
        List<String> known = ['transactions', 'plans', 'categories', 'accounts', 'months',
                              'payees', 'scheduled_transactions']
        List<String> segments = (path ?: '').split(/[?\/]/).findAll { it }
        segments.reverse().find { known.contains(it) } ?: 'resource'
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
