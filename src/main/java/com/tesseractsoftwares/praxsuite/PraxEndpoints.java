package com.tesseractsoftwares.praxsuite;

import java.time.Duration;
import java.util.Map;

/**
 * Calls a workspace's custom gateway endpoints.
 *
 * <p>An endpoint runs one of your automations on the server:
 *
 * <pre>{@code
 * Map<String, Object> result = prax.endpoints().call(endpointId, Map.of("score", score));
 * }</pre>
 *
 * <p>Two things were measured against a live gateway on 2026-08-24, and both change how this should
 * be used rather than merely how it behaves.
 *
 * <p><b>The endpoint does not authenticate its caller for you.</b> A POST with no credential at all
 * returned 200 and ran the automation. That follows from what an endpoint is - a webhook receiver,
 * and Stripe or Meta cannot hold your workspace credential - but it means putting logic here makes
 * it server-EXECUTED, not automatically server-authoritative. The authority comes from the endpoint
 * verifying who called: a signature secret configured on the endpoint, or the automation checking a
 * verified claim from the session token this SDK attaches. Design accordingly, especially in a
 * Minecraft plugin where the server owner is not necessarily you.
 *
 * <p><b>GET is not usable.</b> {@code GET /{workspace}/endpoint/{id}} never reaches the automation -
 * the gateway consumes it as a Meta/Instagram webhook verification handshake and answers 400 with
 * {@code {"error":"Unsupported hub.mode. Expected 'subscribe'."}}. Confirmed to be the route rather
 * than any one endpoint: a nonexistent endpoint id answers identically. There is therefore no GET
 * helper here, and two sibling SDKs had to have theirs removed.
 */
public final class PraxEndpoints {

    /**
     * A Sync endpoint holds the connection open while its automation runs, bounded by the endpoint's
     * own {@code syncTimeoutSeconds} - values of 30, 45, 60 and 90 were all observed in a single
     * workspace. The client's default timeout is below every one of those, so an endpoint call gets
     * its own generous default rather than abandoning work the server is still doing.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(100);

    private final Praxsuite client;

    PraxEndpoints(Praxsuite client) {
        this.client = client;
    }

    /** POSTs to an endpoint with the default 100-second timeout. */
    public Map<String, Object> call(String endpointId, Object body) {
        return call(endpointId, body, DEFAULT_TIMEOUT);
    }

    /** POSTs to an endpoint with no body. */
    public Map<String, Object> call(String endpointId) {
        return call(endpointId, null, DEFAULT_TIMEOUT);
    }

    /**
     * POSTs to an endpoint and returns the automation's response.
     *
     * <p>The body comes back EXACTLY as the automation returned it. Endpoint responses are not
     * platform-enveloped: measured top-level keys were the automation's own, with {@code isSuccess},
     * {@code data}, {@code message}, {@code errors} and {@code statusCode} all absent. Two sibling
     * SDKs were unwrapping {@code .data} here, which was harmless only while no automation returned
     * a top-level {@code data} object - and {@code {"ok":true,"data":{...}}} is an entirely ordinary
     * automation response.
     *
     * <p>Never retried automatically: an endpoint runs an automation, and running one twice is
     * rarely harmless.
     *
     * @param endpointId the endpoint's id from the workspace's API Gateway screen. The other SDKs
     *                   call this a "slug", but the gateway addresses endpoints by GUID.
     */
    public Map<String, Object> call(String endpointId, Object body, Duration timeout) {
        if (endpointId == null || endpointId.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "An endpoint id is required.");
        }
        String url = Routes.endpoint(client.baseUrl(), client.workspaceId(), endpointId.trim());
        return client.send("POST", url, body, false, timeout == null ? DEFAULT_TIMEOUT : timeout);
    }
}
