package ynabbankofdad.ynab

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.URI
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    static final String WRITE_ATTEMPT_TELEMETRY_ENV = 'YNAB_WRITE_ATTEMPT_TELEMETRY_FILE'
    static final List<String> RATE_LIMIT_HEADER_NAMES = [
        'Retry-After',
        'RateLimit-Reset',
        'X-RateLimit-Reset',
        'X-Rate-Limit-Reset',
        'RateLimit-Remaining',
        'X-RateLimit-Remaining',
        'X-Rate-Limit-Remaining',
        'RateLimit-Limit',
        'X-RateLimit-Limit',
        'X-Rate-Limit',
    ].asImmutable()

    final String baseUrl
    final List<String> accessTokens
    final HttpClient httpClient
    final JsonSlurper jsonSlurper = new JsonSlurper()
    final Duration requestTimeout
    /** Null means retry 429 responses until a non-429 response is received. Counts backoff sleeps only. */
    final Integer maxRateLimitRetries
    final Closure sleeper
    final Closure clock
    final Path writeAttemptTelemetryPath
    final Closure infoLogger
    private int tokenIndex = 0

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
        this(baseUrl, accessToken, httpClient, requestTimeout, maxRateLimitRetries, sleeper, clock,
            writeAttemptTelemetryPathFromEnvironment())
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout,
                   Integer maxRateLimitRetries, Closure sleeper, Closure clock,
                   Path writeAttemptTelemetryPath) {
        this(baseUrl, accessToken, httpClient, requestTimeout, maxRateLimitRetries, sleeper, clock,
            writeAttemptTelemetryPath, null)
    }

    YnabHttpClient(String baseUrl, String accessToken, HttpClient httpClient, Duration requestTimeout,
                   Integer maxRateLimitRetries, Closure sleeper, Closure clock,
                   Path writeAttemptTelemetryPath, Closure infoLogger) {
        if (maxRateLimitRetries != null && maxRateLimitRetries < 0) {
            throw new IllegalArgumentException('maxRateLimitRetries must be non-negative')
        }
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
        this.accessTokens = parseAccessTokens(accessToken)
        if (this.accessTokens.isEmpty()) {
            throw new IllegalArgumentException('access tokens must not be empty')
        }
        this.httpClient = httpClient
        this.requestTimeout = requestTimeout
        this.maxRateLimitRetries = maxRateLimitRetries
        this.sleeper = sleeper
        this.clock = clock
        this.writeAttemptTelemetryPath = writeAttemptTelemetryPath
        this.infoLogger = infoLogger
    }

    static List<String> parseAccessTokens(String raw) {
        if (raw == null) {
            return []
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>()
        raw.split(',', -1).each { String segment ->
            String token = segment.trim()
            if (token) {
                unique.add(token)
            }
        }
        return new ArrayList<>(unique)
    }

    def getJson(String path) {
        return sendJson('GET', path, null, [] as Set).body
    }

    YnabHttpResponse getJsonWithMetadata(String path, Set<Integer> acceptedStatuses = [] as Set) {
        sendJson('GET', path, null, acceptedStatuses)
    }

    def postJson(String path, Object payload) {
        return sendJson('POST', path, JsonOutput.toJson(payload), [] as Set).body
    }

    YnabHttpResponse postJsonWithMetadata(String path, Object payload) {
        return sendJson('POST', path, JsonOutput.toJson(payload), [] as Set)
    }

    YnabHttpResponse putJsonWithMetadata(String path, Object payload, Set<Integer> acceptedStatuses = [] as Set) {
        sendJson('PUT', path, JsonOutput.toJson(payload), acceptedStatuses)
    }

    YnabHttpResponse patchJsonWithMetadata(String path, Object payload, Set<Integer> acceptedStatuses = [] as Set) {
        sendJson('PATCH', path, JsonOutput.toJson(payload), acceptedStatuses)
    }

    YnabHttpResponse deleteJsonWithMetadata(String path, Set<Integer> acceptedStatuses = [] as Set) {
        sendJson('DELETE', path, null, acceptedStatuses)
    }

    private HttpRequest buildRequest(String method, String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
            .timeout(requestTimeout)
            .header('Authorization', "Bearer ${accessTokens[tokenIndex]}")
            .header('Accept', 'application/json')
        if (jsonBody != null) {
            builder.header('Content-Type', 'application/json')
        }
        HttpRequest.BodyPublisher body = jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody)
        switch (method) {
            case 'GET':
                return builder.GET().build()
            case 'POST':
                return builder.POST(body).build()
            case 'PUT':
                return builder.PUT(body).build()
            case 'PATCH':
                return builder.method('PATCH', body).build()
            case 'DELETE':
                return builder.DELETE().build()
            default:
                throw new IllegalArgumentException("Unsupported HTTP method ${method}")
        }
    }

    private URI resolve(String path) {
        String normalized = path.startsWith('/') ? path : "/${path}"
        return URI.create(baseUrl + normalized)
    }

    private YnabHttpResponse sendJson(String method, String path, String jsonBody, Set<Integer> acceptedStatuses) {
        HttpResponse<String> response
        int backoffCycles = 0
        String resourceClass = resourceClass(path)
        Set<Integer> triedThisCall = new LinkedHashSet<>()
        List<TokenDelay> delaysThisCycle = []
        while (true) {
            recordWriteAttempt(method, resourceClass)
            HttpRequest request = buildRequest(method, path, jsonBody)
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
            RateLimitObservation observation = inspectRateLimit(response, currentInstant())
            emitInfo(rateLimitInfoMessage(method, resourceClass, observation))
            triedThisCall.add(tokenIndex)
            delaysThisCycle << new TokenDelay(tokenIndex, observation.retryDelay)
            Integer nextToken = nextUnusedTokenIndex(triedThisCall)
            if (nextToken != null) {
                tokenIndex = nextToken
                continue
            }
            if (maxRateLimitRetries != null && backoffCycles >= maxRateLimitRetries) {
                throw new IllegalStateException("YNAB ${method} ${resourceClass} failed with status 429")
            }
            Duration delay = backoffDelay(delaysThisCycle, backoffCycles + 1)
            log.warn('YNAB {} {} was rate limited; retrying after {}', method, resourceClass, delay)
            try {
                sleeper.call(delay)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new IllegalStateException("YNAB ${method} ${resourceClass} interrupted while waiting to retry", e)
            }
            backoffCycles++
            tokenIndex = soonestTokenIndex(delaysThisCycle)
            triedThisCall.clear()
            delaysThisCycle.clear()
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

    private Integer nextUnusedTokenIndex(Set<Integer> triedThisCall) {
        if (triedThisCall.size() >= accessTokens.size()) {
            return null
        }
        int start = tokenIndex
        for (int step = 1; step <= accessTokens.size(); step++) {
            int candidate = (start + step) % accessTokens.size()
            if (!triedThisCall.contains(candidate)) {
                return candidate
            }
        }
        return null
    }

    private static int soonestTokenIndex(List<TokenDelay> delaysThisCycle) {
        TokenDelay soonest = delaysThisCycle.findAll { it.delay != null }
            .min { it.delay }
        return soonest != null ? soonest.tokenIndex : 0
    }

    private static Duration backoffDelay(List<TokenDelay> delaysThisCycle, int backoffNumber) {
        List<Duration> parsed = delaysThisCycle.findAll { it.delay != null }.collect { it.delay }
        if (parsed) {
            return parsed.min()
        }
        return cap(Duration.ofSeconds(5L * backoffNumber))
    }

    private void recordWriteAttempt(String method, String resourceClass) {
        if (method == 'GET' || writeAttemptTelemetryPath == null) {
            return
        }
        String record = JsonOutput.toJson([method: method, resource_class: resourceClass]) + '\n'
        try {
            Files.writeString(writeAttemptTelemetryPath, record, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (IOException | RuntimeException ignored) {
            throw new IllegalStateException(
                "YNAB ${method} ${resourceClass} could not record write attempt")
        }
    }

    private static Path writeAttemptTelemetryPathFromEnvironment() {
        String configured = System.getenv(WRITE_ATTEMPT_TELEMETRY_ENV)
        configured == null || configured.isBlank() ? null : Path.of(configured)
    }

    private Instant currentInstant() {
        def value = clock.call()
        if (!(value instanceof Instant)) {
            throw new IllegalStateException('YNAB retry clock did not return an Instant')
        }
        (Instant) value
    }

    private RateLimitObservation inspectRateLimit(HttpResponse<String> response, Instant now) {
        List<String> present = []
        RATE_LIMIT_HEADER_NAMES.each { String name ->
            if (firstHeaderIgnoreCase(response, name)) {
                present << name
            }
        }
        Integer remaining = parseRemaining(response)
        Duration retryDelay = parseResumeDelay(response, now)
        boolean hasMetadata = remaining != null || retryDelay != null
        new RateLimitObservation(remaining, retryDelay, present, hasMetadata)
    }

    private Integer parseRemaining(HttpResponse<String> response) {
        for (String name : ['RateLimit-Remaining', 'X-RateLimit-Remaining', 'X-Rate-Limit-Remaining']) {
            String raw = firstHeaderIgnoreCase(response, name)
            if (!raw) {
                continue
            }
            try {
                return Integer.parseInt(raw.trim())
            } catch (NumberFormatException ignored) {
                // Try the next remaining header.
            }
        }
        String legacy = firstHeaderIgnoreCase(response, 'X-Rate-Limit')
        if (legacy && legacy.contains('/')) {
            try {
                List<String> parts = legacy.split('/', 2).collect { it.trim() }
                int used = Integer.parseInt(parts[0])
                int limit = Integer.parseInt(parts[1])
                return limit - used
            } catch (NumberFormatException ignored) {
                // Fall through to body fields.
            }
        }
        return bodyInteger(response.body(), 'remaining')
    }

    private Duration parseResumeDelay(HttpResponse<String> response, Instant now) {
        String header = firstHeaderIgnoreCase(response, 'Retry-After')
        if (header) {
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
        }
        for (String name : ['RateLimit-Reset', 'X-RateLimit-Reset', 'X-Rate-Limit-Reset']) {
            String reset = firstHeaderIgnoreCase(response, name)
            if (!reset) {
                continue
            }
            try {
                Instant resume = Instant.ofEpochSecond(Long.parseLong(reset))
                if (resume.isAfter(now)) {
                    return cap(Duration.between(now, resume))
                }
            } catch (NumberFormatException | DateTimeException ignored) {
                // Continue to the next common reset header.
            }
        }
        Integer bodyRetry = bodyInteger(response.body(), 'retry_after')
        if (bodyRetry != null && bodyRetry > 0) {
            return cap(Duration.ofSeconds(bodyRetry.longValue()))
        }
        Integer bodyReset = bodyInteger(response.body(), 'reset')
        if (bodyReset != null) {
            try {
                Instant resume = Instant.ofEpochSecond(bodyReset.longValue())
                if (resume.isAfter(now)) {
                    return cap(Duration.between(now, resume))
                }
            } catch (DateTimeException ignored) {
                if (bodyReset > 0) {
                    return cap(Duration.ofSeconds(bodyReset.longValue()))
                }
            }
        }
        return null
    }

    private boolean bodyHasRemainingOrReset(String bodyText) {
        def parsed = parseJsonBody(bodyText)
        if (!(parsed instanceof Map)) {
            return false
        }
        def error = parsed.error instanceof Map ? parsed.error : parsed
        ['remaining', 'reset', 'limit', 'retry_after'].any { error[it] != null }
    }

    private Integer bodyInteger(String bodyText, String field) {
        def parsed = parseJsonBody(bodyText)
        if (!(parsed instanceof Map)) {
            return null
        }
        def error = parsed.error instanceof Map ? parsed.error : parsed
        def value = error[field]
        if (value instanceof Number) {
            return value.intValue()
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(value.trim())
            } catch (NumberFormatException ignored) {
                return null
            }
        }
        return null
    }

    private def parseJsonBody(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            return null
        }
        try {
            return jsonSlurper.parseText(bodyText)
        } catch (RuntimeException ignored) {
            return null
        }
    }

    private String rateLimitInfoMessage(String method, String resourceClass, RateLimitObservation observation) {
        int slot = tokenIndex + 1
        int total = accessTokens.size()
        String prefix = "YNAB ${method} ${resourceClass} rate limited on token ${slot} of ${total}"
        List<String> parts = [prefix]
        if (observation.remaining != null) {
            parts << "remaining=${observation.remaining}"
        }
        if (observation.retryDelay != null) {
            parts << "retry after ${observation.retryDelay}"
        }
        if (observation.headerNames) {
            parts << "headers=${observation.headerNames.join(',')}"
        }
        if (!observation.hasMetadata) {
            parts << 'no remaining/reset metadata'
        }
        return parts.join('; ')
    }

    private void emitInfo(String message) {
        if (infoLogger != null) {
            infoLogger.call(message)
        } else {
            log.info(message)
        }
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

    private static class RateLimitObservation {
        final Integer remaining
        final Duration retryDelay
        final List<String> headerNames
        final boolean hasMetadata

        RateLimitObservation(Integer remaining, Duration retryDelay, List<String> headerNames, boolean hasMetadata) {
            this.remaining = remaining
            this.retryDelay = retryDelay
            this.headerNames = headerNames
            this.hasMetadata = hasMetadata
        }
    }

    private static class TokenDelay {
        final int tokenIndex
        final Duration delay

        TokenDelay(int tokenIndex, Duration delay) {
            this.tokenIndex = tokenIndex
            this.delay = delay
        }
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
