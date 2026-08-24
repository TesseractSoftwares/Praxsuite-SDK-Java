package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The one place this SDK talks to the network.
 *
 * <p>Built on {@link HttpClient} from the standard library - Java 11+ - so the SDK needs no HTTP
 * dependency. That matters more here than elsewhere in the portfolio: a Paper/Spigot plugin runs
 * inside a server classloader that already holds its own copies of common libraries, and a shaded
 * OkHttp or Apache client is a classic way for a plugin to break a server it did not ship with.
 *
 * <p>One {@link HttpClient} is shared for the life of a {@link Praxsuite} instance, so connections
 * are pooled and HTTP/2 is used where the gateway offers it.
 */
class Http {

    // Not final, and requestJson is overridable, purely so tests can substitute a recording
    // stub. Both are package-private, so this widens nothing for consumers.

    /**
     * Retries are only ever attempted for transient failures, and only for idempotent requests.
     * Retrying a failed insert is how you get two rows.
     */
    static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 400;

    private final HttpClient client;
    private final Duration defaultTimeout;
    private final int maxAttempts;

    Http(Duration defaultTimeout, int maxAttempts) {
        this.defaultTimeout = defaultTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.client = HttpClient.newBuilder()
            // The gateway's TLS is public; the default trust store is correct. Connect timeout is
            // separate from the per-request timeout so a dead host fails fast even when a slow
            // automation is allowed 90 seconds.
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Sends one request and returns the decoded JSON object.
     *
     * @param retrySafe must only be true for an operation that can be repeated without changing
     *                  state.
     * @param timeout   overrides the client default. Endpoint calls need this: a Sync endpoint
     *                  holds the connection while its automation runs.
     * @throws PraxError on any failure, already classified.
     */
    Map<String, Object> requestJson(String method, String url, Map<String, String> headers,
                                    Object body, boolean retrySafe, Duration timeout) {
        int attempts = retrySafe ? maxAttempts : 1;
        PraxError last = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                long delay = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
                PraxLog.debug("Retrying in " + delay + "ms (attempt " + (attempt + 1)
                    + " of " + attempts + ")");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    // Preserve the interrupt rather than swallowing it: a caller shutting the
                    // thread down must not be blocked by our backoff.
                    Thread.currentThread().interrupt();
                    throw new PraxNetworkError("INTERRUPTED",
                        "The request was interrupted while backing off.", 0, List.of(), "");
                }
            }
            try {
                return sendOnce(method, url, headers, body, timeout);
            } catch (PraxError e) {
                last = e;
                if (!e.isTransient()) throw e;
            }
        }
        throw last == null
            ? new PraxNetworkError("NETWORK_ERROR", "The request failed.", 0, List.of(), "")
            : last;
    }

    private Map<String, Object> sendOnce(String method, String url, Map<String, String> headers,
                                          Object body, Duration timeout) {
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout != null ? timeout : defaultTimeout)
            .method(method, publisher)
            .header("Accept", "application/json");

        if (body != null) b.header("Content-Type", "application/json");
        headers.forEach(b::header);

        PraxLog.debug("-> " + method + " " + url);

        HttpResponse<String> response;
        try {
            response = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new PraxTimeoutError("TIMEOUT",
                "The request timed out before the gateway answered. A Sync endpoint can hold the "
                    + "connection for up to its own syncTimeoutSeconds; raise the timeout if that "
                    + "is what you are calling.", 0, List.of(), "");
        } catch (IOException e) {
            throw transportError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PraxNetworkError("INTERRUPTED", "The request was interrupted.",
                0, List.of(), "");
        }

        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();
        PraxLog.debug("<- " + status + " " + url + " (" + raw.length() + " chars)");

        if (status < 200 || status > 299) throw Responses.parseError(status, raw);

        // 204 and an empty 200 are both legitimate: a logout returns no body.
        if (raw.isBlank()) return Map.of();

        Object parsed = Json.readOrNull(raw);
        if (!(parsed instanceof Map)) {
            throw new PraxError("MALFORMED_RESPONSE",
                "The gateway returned HTTP " + status + " with a body that is not a JSON object.",
                status, List.of(), raw);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    private static PraxError transportError(IOException e) {
        String text = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = text.toLowerCase();
        if (lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls")) {
            return new PraxNetworkError("NETWORK_ERROR",
                "TLS failed talking to the gateway: " + text + ". On a stripped container image "
                    + "this is usually a missing CA bundle.", 0, List.of(), "");
        }
        return new PraxNetworkError("NETWORK_ERROR",
            "Could not reach the gateway: " + text, 0, List.of(), "");
    }
}
