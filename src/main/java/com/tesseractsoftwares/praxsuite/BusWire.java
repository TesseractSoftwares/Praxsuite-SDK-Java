package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Event Bus wire format: SignalR's JSON hub protocol, version 1.
 *
 * <p>Everything here is pure and synchronous so it can be tested with no socket, which is how the
 * conformance cases in {@code cases/event-bus.json} are run.
 *
 * <p>The protocol is spoken directly rather than through {@code com.microsoft.signalr}. That client
 * drags in RxJava and OkHttp, and this SDK's whole point is that a Paper plugin can drop it into a
 * server classloader without a version-skew argument. The surface used here is four message types
 * wide.
 */
public final class BusWire {

    private BusWire() { }

    /** ASCII record separator. SignalR terminates every frame with it. */
    public static final char RECORD_SEPARATOR = '\u001e';

    /**
     * The handshake, byte for byte. SignalR compares it literally - a trailing newline or a space
     * after a colon fails it, with an error that does not say so.
     */
    public static final String HANDSHAKE_FRAME = "{\"protocol\":\"json\",\"version\":1}";

    /** The hub's path. There is no workspace segment: the workspace comes from the token. */
    public static final String BUS_PATH = "/hubs/event-bus";

    public static final int MESSAGE_INVOCATION = 1;
    public static final int MESSAGE_COMPLETION = 3;
    public static final int MESSAGE_PING = 6;
    public static final int MESSAGE_CLOSE = 7;

    /** Whole frames plus whatever trailing fragment was left unparsed. */
    public record Frames(List<String> frames, String remainder) { }

    /**
     * Splits a received buffer into whole frames.
     *
     * <p>Two things go wrong without this. A single physical message can carry SEVERAL frames, so
     * parsing the whole buffer as JSON throws exactly when traffic picks up - the load the bus
     * exists for. And a transport may split one frame across two reads, so the tail is kept rather
     * than parsed or discarded.
     */
    public static Frames splitFrames(String buffer) {
        List<String> frames = new ArrayList<>();
        if (buffer == null || buffer.isEmpty()) {
            return new Frames(frames, "");
        }

        int start = 0;
        int separator;
        while ((separator = buffer.indexOf(RECORD_SEPARATOR, start)) >= 0) {
            if (separator > start) {
                frames.add(buffer.substring(start, separator));
            }
            start = separator + 1;
        }
        return new Frames(frames, buffer.substring(start));
    }

    /** Wraps a frame for sending. */
    public static String frame(String payload) {
        return payload + RECORD_SEPARATOR;
    }

    /**
     * Folds a bus key the way the server does: the TOPIC segment to lowercase, the instance
     * untouched.
     *
     * <p>{@code BusAddress.ForCaller} folds the topic both when it resolves the topic and when it
     * builds the SignalR group name, so {@code Office:hq} and {@code office:hq} are one bus.
     * Folding the whole key instead would merge {@code office:HQ} and {@code office:hq}, which are
     * two genuinely different buses. Fold the same half the server folds and neither mistake is
     * possible.
     */
    public static String normalizeBusKey(String busKey) {
        if (busKey == null) {
            return "";
        }

        String key = busKey.strip();
        if (key.isEmpty()) {
            return "";
        }

        int separator = key.indexOf(':');
        if (separator <= 0) {
            return key.toLowerCase(Locale.ROOT);
        }
        return key.substring(0, separator).toLowerCase(Locale.ROOT) + key.substring(separator);
    }

    /**
     * Rejects keys the server would reject anyway, without spending a round trip on it.
     *
     * @return the normalised key
     * @throws PraxValidationError when the server would refuse it
     */
    public static String requireValidBusKey(String busKey) {
        String key = normalizeBusKey(busKey);

        if (key.isEmpty()) {
            throw new PraxValidationError("INVALID_BUS_KEY",
                "A bus key is required. It looks like \"topic:instance\", e.g. \"office:hq\".");
        }
        // The group name is built by concatenation, so a key carrying the separator could climb
        // out of its own segment and name another workspace's group.
        if (key.contains("ws:")) {
            throw new PraxValidationError("INVALID_BUS_KEY",
                "A bus key may not contain \"ws:\" (got \"" + busKey + "\"). The server refuses it.");
        }
        if (key.length() > 200) {
            throw new PraxValidationError("INVALID_BUS_KEY",
                "Bus key is too long (" + key.length() + " characters).");
        }
        return key;
    }

    /**
     * Builds an invocation frame. {@code invocationId} is a STRING - SignalR matches completions on
     * it by value, and a numeric id never matches.
     */
    public static String buildInvocation(String invocationId, String target, List<Object> args) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", MESSAGE_INVOCATION);
        message.put("invocationId", invocationId);
        message.put("target", target);
        message.put("arguments", args == null ? List.of() : args);
        return Json.write(message);
    }

    /** One peer's last known state within a bus. */
    public record PeerState(String userId, String event, Object payload) { }

    /**
     * What the hub returns from JoinBus, Publish and LeaveBus, in one shape.
     *
     * @param ok               false for a policy rejection; rejections arrive INSIDE a successful
     *                         completion, which is why this is a field rather than an exception
     * @param error            one of the hub's error codes, or null
     * @param peers            JoinBus: every peer's last retained message
     * @param recipients       Publish: how many OTHER connections it reached; zero is success
     * @param isTransportError true when SignalR itself failed the call - a server fault rather than
     *                         a policy decision, which wants different handling
     */
    public record BusResult(boolean ok, String error, List<PeerState> peers, int recipients,
                            boolean isTransportError) {

        static BusResult okVoid() {
            return new BusResult(true, null, List.of(), 0, false);
        }
    }

    /**
     * Reads a completion frame.
     *
     * <p>The trap this exists for: the hub answers a REJECTED call with a SUCCESSFUL completion
     * whose result carries {@code ok:false}. Code that only inspects SignalR's {@code error} field
     * reports every denied join as a success. And {@code LeaveBus} is void, so its result is
     * literally {@code null}.
     */
    @SuppressWarnings("unchecked")
    public static BusResult parseBusResult(Map<String, Object> message) {
        if (message == null) {
            return BusResult.okVoid();
        }

        Object transportError = message.get("error");
        if (transportError instanceof String text && !text.isEmpty()) {
            return new BusResult(false, text, List.of(), 0, true);
        }

        Object body = message.get("result");
        if (!(body instanceof Map)) {
            return BusResult.okVoid();   // void, e.g. LeaveBus
        }
        Map<String, Object> result = (Map<String, Object>) body;

        boolean ok = !Boolean.FALSE.equals(result.get("ok"));
        String error = result.get("error") instanceof String code ? code : null;

        int recipients = result.get("recipients") instanceof Number number ? number.intValue() : 0;

        List<PeerState> peers = new ArrayList<>();
        if (result.get("peers") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map) {
                    Map<String, Object> peer = (Map<String, Object>) entry;
                    peers.add(new PeerState(
                        asString(peer.get("userId")),
                        asString(peer.get("event")),
                        peer.get("payload")));
                }
            }
        }

        return new BusResult(ok, error, List.copyOf(peers), recipients, false);
    }

    /** Negotiate: a zero-length POST with the end-user JWT as a bearer token. */
    public static String negotiateUrl(String baseUrl) {
        return Routes.normalizeBaseUrl(baseUrl) + BUS_PATH + "/negotiate?negotiateVersion=1";
    }

    /**
     * The WebSocket URL, with the session token in the query string.
     *
     * <p>The token goes in the query because the hub accepts {@code access_token} for exactly that
     * reason - a browser WebSocket cannot set headers - and keeping one placement across every
     * runtime is what makes this transport identical in every SDK.
     */
    public static String socketUrl(String baseUrl, String accessToken) {
        String http = Routes.normalizeBaseUrl(baseUrl);
        String ws;
        if (http.regionMatches(true, 0, "https://", 0, 8)) {
            ws = "wss://" + http.substring(8);
        } else if (http.regionMatches(true, 0, "http://", 0, 7)) {
            ws = "ws://" + http.substring(7);
        } else {
            ws = http;
        }
        String token = accessToken == null ? "" : accessToken;
        return ws + BUS_PATH + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /**
     * Turns a hub error code into a sentence worth reading. The codes are stable and are what
     * callers should branch on; these strings are not.
     */
    public static String describeError(String code) {
        if (code == null || code.isEmpty()) {
            return "The bus refused the call.";
        }
        return switch (code) {
            case "unknown_topic" -> "That topic is not declared in this workspace. Buses are never "
                + "auto-created - declare the topic under API Gateway / Event Bus first.";
            case "denied" -> "The topic refused this user. Check its access mode: Workspace, Roles "
                + "(read straight from the JWT), or Grants (which needs a grant on this exact bus "
                + "instance).";
            case "invalid_ticket" -> "The ticket was missing, expired, or minted for a different "
                + "user, workspace or bus.";
            case "not_a_member" -> "Publish to a bus this connection has not joined. Join it first "
                + "- membership is the authorization check on the publish path.";
            case "invalid_bus_key" -> "The key is malformed, or it named another user's \"user:\" "
                + "bus. Only \"user:self\" is addressable.";
            case "invalid_event_name" -> "The event name was empty or too long.";
            case "payload_too_large" -> "The payload is over this topic's byte limit.";
            case "bus_full_or_too_many_buses" -> "The bus is at its peer limit, or this connection "
                + "already holds as many buses as it may.";
            case "rate_limited" -> "Too many publishes. The limit is priced by RECIPIENTS, so a "
                + "large bus exhausts it faster than a small one.";
            default -> code;
        };
    }

    static String asString(Object value) {
        return value instanceof String text ? text : "";
    }
}
