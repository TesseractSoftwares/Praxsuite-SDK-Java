package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The Prax Event Bus - ephemeral realtime between connected clients.
 *
 * <p>Live cursors, player positions, "is typing", a lobby: state that is CHANGING, where losing a
 * message is fine because a newer one is 100ms behind it.
 *
 * <p><b>Nothing is persisted.</b> No history, no retry, no delivery to somebody who was not
 * connected. The test is one question: if this is lost, does it matter? Yes - a purchase, a score,
 * an inventory grant - means a table via {@code prax.data()}, or an automation, and a
 * server-authoritative one at that. No, because a newer one is coming, means the bus.
 *
 * <p><b>Payloads are hostile.</b> The bus relays opaque JSON between USERS and parses none of it,
 * so every server-side check is bypassed. A position is a hint, never an authority.
 *
 * <p>Requires a signed-in end user: the hub authenticates with the session token, not with the
 * workspace credential.
 *
 * <p>Runs on {@link java.net.http.WebSocket} from the JDK, so it adds no dependency - which matters
 * most in the environment this SDK exists for, a Paper or Spigot plugin inside a server
 * classloader that already has its own copy of half of Maven Central.
 */
public final class PraxBus implements AutoCloseable {

    /** Where the connection is. Reconnecting is normal and resolves itself. */
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private final Praxsuite client;
    private final Map<String, PraxChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<BusWire.BusResult>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<State>> stateListeners = new ArrayList<>();
    private final AtomicInteger nextInvocation = new AtomicInteger();
    private final AtomicBoolean closedByUs = new AtomicBoolean();
    private final Object gate = new Object();

    private final ScheduledExecutorService reconnects =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "praxsuite-event-bus");
            thread.setDaemon(true);
            return thread;
        });

    private WebSocket socket;
    private StringBuilder buffer = new StringBuilder();
    private CompletableFuture<Void> handshake;
    private volatile State state = State.DISCONNECTED;
    private long reconnectDelayMillis;
    private boolean warnedAboutRouting;

    /** Reconnect automatically and re-join every bus that was held. */
    private volatile boolean autoReconnect = true;

    PraxBus(Praxsuite client) {
        this.client = client;
    }

    public State state() {
        return state;
    }

    /** Turns automatic reconnection off. On by default. */
    public PraxBus autoReconnect(boolean value) {
        this.autoReconnect = value;
        return this;
    }

    /** Notified on every transition. Reconnecting is a good moment to grey out a roster. */
    public void onStateChange(Consumer<State> listener) {
        synchronized (stateListeners) {
            stateListeners.add(listener);
        }
    }

    /** A topic by key. Nothing is sent until one of its channels is joined. */
    public PraxTopic topic(String key) {
        return new PraxTopic(this, BusWire.normalizeBusKey(key));
    }

    /**
     * A channel by full key, {@code topic:instance}. Repeated calls return the SAME object, so
     * handlers registered anywhere in the application all fire.
     */
    public PraxChannel channel(String busKey) {
        String key = BusWire.requireValidBusKey(busKey);
        return channels.computeIfAbsent(key, k -> new PraxChannel(this, k));
    }

    /**
     * The caller's own private bus.
     *
     * <p>Addressed as {@code user:self} and resolved server-side to their id - which is what makes
     * it the one bus needing no ticket, since it cannot name anybody else.
     */
    public PraxChannel self() {
        return channel("user:self");
    }

    /**
     * Opens the connection. {@link PraxChannel#join()} calls this for you; call it directly to fail
     * fast at start-up rather than on the first join.
     */
    public void connect() {
        synchronized (gate) {
            if (state == State.CONNECTED) {
                return;
            }
            closedByUs.set(false);
            openSocket();
        }
        rejoinAll();
    }

    /** Closes the connection and stops reconnecting. Channels keep their handlers. */
    @Override
    public void close() {
        closedByUs.set(true);

        WebSocket open;
        synchronized (gate) {
            open = socket;
            socket = null;
            for (PraxChannel channel : channels.values()) {
                channel.wanted = false;
                channel.joined = false;
            }
        }

        if (open != null) {
            try {
                open.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException e) {
                // A socket that will not close politely is closing anyway.
                PraxLog.debug("Event Bus close failed: " + e);
            }
        }
        setState(State.DISCONNECTED);
        reconnects.shutdownNow();
    }

    // ── invocation ──────────────────────────────────────────────────────────

    BusWire.BusResult invoke(String target, List<Object> args) {
        connect();

        WebSocket open;
        synchronized (gate) {
            open = socket;
        }
        if (open == null) {
            throw new PraxError("BUS_NOT_CONNECTED", "The Event Bus connection is not open.");
        }

        String invocationId = Integer.toString(nextInvocation.incrementAndGet());
        CompletableFuture<BusWire.BusResult> completion = new CompletableFuture<>();
        pending.put(invocationId, completion);

        try {
            send(open, BusWire.frame(BusWire.buildInvocation(invocationId, target, args)));
            return completion.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new PraxError("BUS_TIMEOUT",
                "The hub did not answer " + target + " within " + CALL_TIMEOUT.toSeconds() + "s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PraxError("BUS_INTERRUPTED", "Interrupted while waiting for " + target + ".");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PraxError praxError) {
                throw praxError;
            }
            throw new PraxError("BUS_CALL_FAILED", target + " failed: " + cause);
        } finally {
            pending.remove(invocationId);
        }
    }

    // ── connection ──────────────────────────────────────────────────────────

    private void openSocket() {
        PraxAuth.Session session = client.auth().session();
        if (session == null || !session.isValid()) {
            throw new PraxAuthError("BUS_REQUIRES_SESSION",
                "The Event Bus needs a signed-in end user - it authenticates with the session "
                + "token, not with the workspace credential. Call auth().login() (or finish an "
                + "OIDC sign-in) first.", 401, java.util.List.of(), null);
        }

        setState(state == State.DISCONNECTED ? State.CONNECTING : State.RECONNECTING);
        buffer = new StringBuilder();
        handshake = new CompletableFuture<>();

        try {
            // The token rides in the query string because the hub accepts access_token, and one
            // placement across every runtime keeps the transport identical in all six SDKs.
            socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(BusWire.socketUrl(client.baseUrl(), session.accessToken())),
                    new Listener())
                .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            send(socket, BusWire.frame(BusWire.HANDSHAKE_FRAME));
            handshake.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PraxError("BUS_CONNECT_FAILED", "Interrupted while connecting to the bus.");
        } catch (TimeoutException e) {
            socket = null;
            setState(State.DISCONNECTED);
            throw new PraxError("BUS_CONNECT_FAILED",
                "The Event Bus handshake did not complete within " + CONNECT_TIMEOUT.toSeconds()
                + "s. The commonest cause is an expired or rejected session token.");
        } catch (java.util.concurrent.ExecutionException e) {
            socket = null;
            setState(State.DISCONNECTED);
            throw new PraxError("BUS_CONNECT_FAILED",
                "Could not open the Event Bus connection to " + client.baseUrl() + ": "
                + e.getCause());
        }

        reconnectDelayMillis = 0;
        setState(State.CONNECTED);
    }

    private void send(WebSocket open, String text) {
        try {
            // One writer at a time: WebSocket.sendText rejects a second send before the first
            // completes, and every channel shares this one socket.
            synchronized (gate) {
                open.sendText(text, true).get(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PraxError("BUS_SEND_FAILED", "Interrupted while sending to the bus.");
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            throw new PraxError("BUS_SEND_FAILED", "Could not send to the bus: " + e);
        }
    }

    private void onData(String text) {
        buffer.append(text);
        BusWire.Frames split = BusWire.splitFrames(buffer.toString());
        buffer = new StringBuilder(split.remainder());

        for (String frame : split.frames()) {
            handleFrame(frame);
        }
    }

    private void handleFrame(String raw) {
        Map<String, Object> message;
        try {
            message = Json.readObject(raw);
        } catch (RuntimeException e) {
            PraxLog.warn("Discarded an Event Bus frame that is not JSON.");
            return;
        }

        // The handshake answer is the one frame with no type: {} on success, { error } on failure.
        if (handshake != null && !handshake.isDone()) {
            if (message.get("error") instanceof String reason) {
                handshake.completeExceptionally(new PraxError("BUS_HANDSHAKE_REJECTED",
                    "The hub rejected the handshake: " + reason));
            } else {
                handshake.complete(null);
            }
            return;
        }

        int type = message.get("type") instanceof Number number ? number.intValue() : 0;

        switch (type) {
            case BusWire.MESSAGE_PING -> {
                // keepalive - never surface it
            }
            case BusWire.MESSAGE_COMPLETION -> {
                String invocationId = BusWire.asString(message.get("invocationId"));
                CompletableFuture<BusWire.BusResult> waiter = pending.remove(invocationId);
                if (waiter != null) {
                    waiter.complete(BusWire.parseBusResult(message));
                }
            }
            case BusWire.MESSAGE_INVOCATION -> handleServerEvent(message);
            case BusWire.MESSAGE_CLOSE -> PraxLog.warn("The hub closed the connection: "
                + message.getOrDefault("error", "no reason given"));
            default -> PraxLog.debug("Ignoring Event Bus message type " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleServerEvent(Map<String, Object> message) {
        String target = BusWire.asString(message.get("target"));
        Map<String, Object> first = Map.of();
        if (message.get("arguments") instanceof List<?> args && !args.isEmpty()
            && args.get(0) instanceof Map) {
            first = (Map<String, Object>) args.get(0);
        }

        switch (target) {
            case "bus-event" -> {
                for (PraxChannel channel : route(first)) {
                    channel.dispatch(new PraxChannel.BusEvent(
                        channel.key(),
                        BusWire.asString(first.get("fromUserId")),
                        BusWire.asString(first.get("event")),
                        first.get("payload")));
                }
            }
            case "peer-joined", "peer-left" -> {
                String userId = BusWire.asString(first.get("userId"));
                boolean joined = "peer-joined".equals(target);
                for (PraxChannel channel : route(first)) {
                    channel.dispatchPeer(joined, userId);
                }
            }
            case "bus-evicted" -> {
                PraxChannel channel =
                    channels.get(BusWire.normalizeBusKey(BusWire.asString(first.get("bus"))));
                if (channel != null) {
                    channel.dispatchEvicted();
                }
            }
            default -> PraxLog.debug("Ignoring an unknown Event Bus message: " + target);
        }
    }

    /**
     * Decides which channels an inbound message belongs to.
     *
     * <p>The message names its bus, and that is the whole answer. The fallback exists because a
     * gateway older than 2026-09-07 does not send the field: one connection carries every joined
     * bus, and SignalR reports which invocation arrived but never which group it came from, so on
     * such a server a client holding two buses genuinely cannot tell their traffic apart.
     */
    private List<PraxChannel> route(Map<String, Object> message) {
        String named = BusWire.normalizeBusKey(BusWire.asString(message.get("bus")));
        if (!named.isEmpty()) {
            PraxChannel channel = channels.get(named);
            return channel == null ? List.of() : List.of(channel);
        }

        List<PraxChannel> joined = new ArrayList<>();
        for (PraxChannel channel : channels.values()) {
            if (channel.joined) {
                joined.add(channel);
            }
        }

        if (joined.size() > 1 && !warnedAboutRouting) {
            warnedAboutRouting = true;
            PraxLog.warn("This gateway sends bus messages without naming their bus, so events "
                + "cannot be routed to the channel they came from. Handlers on every joined "
                + "channel will see them. Update the gateway, or hold one bus per connection "
                + "until you can.");
        }
        return joined;
    }

    private void onClosed(String why) {
        synchronized (gate) {
            socket = null;
            for (PraxChannel channel : channels.values()) {
                channel.joined = false;
            }
        }

        for (CompletableFuture<BusWire.BusResult> waiter : pending.values()) {
            waiter.completeExceptionally(new PraxError("BUS_DISCONNECTED",
                "The connection closed before the call completed."));
        }
        pending.clear();

        if (closedByUs.get() || !autoReconnect) {
            setState(State.DISCONNECTED);
            return;
        }

        setState(State.RECONNECTING);
        reconnectDelayMillis = reconnectDelayMillis == 0
            ? 1000L
            : Math.min(reconnectDelayMillis * 2, 30_000L);

        PraxLog.info("Event Bus connection closed (" + why + "); reconnecting in "
            + reconnectDelayMillis + "ms.");

        try {
            reconnects.schedule(() -> {
                if (closedByUs.get()) {
                    return;
                }
                try {
                    connect();
                } catch (RuntimeException e) {
                    PraxLog.warn("Event Bus reconnect failed: " + e.getMessage());
                    onClosed("reconnect failed");
                }
            }, reconnectDelayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            PraxLog.debug("Event Bus reconnect not scheduled; the bus is shutting down.");
        }
    }

    /**
     * Re-joins every bus the application still wants.
     *
     * <p>Not optional bookkeeping. SignalR group membership does not survive a reconnect, so a
     * client that reconnects and stops there is connected and in no groups - receiving nothing,
     * reporting no error, and looking for all the world like a broken server.
     *
     * <p>Re-joining calls JoinBus again, which re-runs the topic's access rule. The SDK never
     * replays a membership list for the server to take on faith.
     */
    private void rejoinAll() {
        for (PraxChannel channel : channels.values()) {
            if (!channel.wanted || channel.joined) {
                continue;
            }
            try {
                channel.join();
                PraxLog.info("Re-joined \"" + channel.key() + "\" after reconnecting.");
            } catch (RuntimeException e) {
                PraxLog.warn("Could not re-join \"" + channel.key() + "\": " + e.getMessage());
            }
        }
    }

    private void setState(State next) {
        if (state == next) {
            return;
        }
        state = next;

        List<Consumer<State>> listeners;
        synchronized (stateListeners) {
            listeners = List.copyOf(stateListeners);
        }
        for (Consumer<State> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                PraxLog.warn("A bus state listener threw: " + e);
            }
        }
    }

    /** Feeds the JDK's WebSocket callbacks into the frame handler. */
    private final class Listener implements WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // The JDK may deliver one message in several parts; onData reassembles frames anyway,
            // so partial text is simply appended.
            webSocket.request(1);
            try {
                onData(data.toString());
            } catch (RuntimeException e) {
                PraxLog.warn("An Event Bus frame could not be handled: " + e);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed("code " + statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (handshake != null && !handshake.isDone()) {
                handshake.completeExceptionally(error);
            }
            onClosed(String.valueOf(error));
        }
    }
}
