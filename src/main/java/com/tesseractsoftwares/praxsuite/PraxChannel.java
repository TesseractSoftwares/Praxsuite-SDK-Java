package com.tesseractsoftwares.praxsuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A single bus - one topic, one instance. This is the object you actually work with.
 *
 * <p>Handlers registered before {@link #join()} are kept, so a channel can be wired up once at
 * start-up and joined later, and they survive a reconnect: the bus re-joins on your behalf and the
 * same handlers keep firing.
 *
 * <p>Handlers run on the WebSocket's own thread. Anything that must happen on a game or server
 * tick - a Bukkit scheduler task, a UI update - should be posted from inside the handler rather
 * than done in it.
 */
public final class PraxChannel {

    /**
     * An event relayed from another peer.
     *
     * @param bus        the bus it arrived on
     * @param fromUserId stamped by the server from the validated token, never read from the
     *                   payload, so a peer cannot claim to be somebody else
     * @param event      the name the publisher chose; the bus never interprets it
     * @param payload    UNTRUSTED. The bus relays opaque JSON between USERS and parses none of it,
     *                   so every server-side check is bypassed. A position is a hint, never an
     *                   authority.
     */
    public record BusEvent(String bus, String fromUserId, String event, Object payload) { }

    private final PraxBus bus;
    private final String key;

    private final Map<String, List<Consumer<BusEvent>>> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<BusEvent>> anyHandlers = new ArrayList<>();
    private final List<Consumer<String>> joinedHandlers = new ArrayList<>();
    private final List<Consumer<String>> leftHandlers = new ArrayList<>();
    private final List<Runnable> evictedHandlers = new ArrayList<>();

    private volatile List<BusWire.PeerState> peers = List.of();
    private volatile String ticket;

    /** Whether the app wants to be in this bus. Drives the re-join after a reconnect. */
    volatile boolean wanted;
    volatile boolean joined;

    PraxChannel(PraxBus bus, String key) {
        this.bus = bus;
        this.key = key;
    }

    /** The normalised key, e.g. {@code office:hq}. */
    public String key() {
        return key;
    }

    /** The topic segment - everything before the first colon. */
    public String topic() {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }

    /** The instance segment - everything after the first colon. */
    public String instance() {
        int i = key.indexOf(':');
        return i < 0 ? "" : key.substring(i + 1);
    }

    /**
     * Every peer's last retained message, as of the most recent join.
     *
     * <p>This is what stops a late joiner staring at an empty room until somebody moves. It is a
     * snapshot, not a live view - what follows arrives through {@link #on}.
     */
    public List<BusWire.PeerState> peers() {
        return peers;
    }

    /**
     * Joins the bus, returning the peers already present.
     *
     * <p>A refused join THROWS. That is deliberately louder than a refused publish: a publish that
     * does not land is one dropped frame, whereas a join that does not land leaves this client
     * silently absent for the whole session.
     */
    public List<BusWire.PeerState> join() {
        return join(null);
    }

    /**
     * Joins with a ticket.
     *
     * <p>Only consulted for topics whose access mode is Ticket, and remembered so a reconnect can
     * re-join with it. Ticket topics are not usable yet - nothing in the platform mints a ticket -
     * so leave this null unless you were told otherwise.
     */
    public List<BusWire.PeerState> join(String joinTicket) {
        wanted = true;
        if (joinTicket != null) {
            ticket = joinTicket;
        }

        List<Object> args = new ArrayList<>(2);
        args.add(key);
        args.add(ticket);

        BusWire.BusResult result = bus.invoke("JoinBus", args);
        if (!result.ok()) {
            wanted = false;
            String code = result.isTransportError()
                ? "BUS_CALL_FAILED"
                : "BUS_" + (result.error() == null ? "DENIED" : result.error().toUpperCase(
                    java.util.Locale.ROOT));
            throw new PraxError(code,
                "Could not join \"" + key + "\": " + BusWire.describeError(result.error()));
        }

        joined = true;
        peers = result.peers();
        return peers;
    }

    /**
     * Sends an event to every OTHER peer in the bus.
     *
     * <p>Does NOT throw when the bus refuses the frame - dropping an ephemeral message is ordinary
     * operation, and a tick loop that throws on a rate limit is worse than one that skips a frame.
     * Read the result when you care:
     *
     * <pre>{@code
     * var r = room.publish("move", Map.of("x", x, "y", y));
     * if (!r.ok()) log(r.error());          // e.g. "rate_limited"
     * if (r.recipients() == 0) { }          // it went out, and nobody was joined
     * }</pre>
     *
     * <p>You will not receive your own event back. Apply your own change locally.
     */
    public BusWire.BusResult publish(String event, Object payload) {
        return bus.invoke("Publish", List.of(key, event, payload == null ? Map.of() : payload));
    }

    /** Leaves the bus. Idempotent, and it stops the reconnect logic re-joining. */
    public void leave() {
        wanted = false;
        joined = false;
        peers = List.of();
        if (bus.state() == PraxBus.State.CONNECTED) {
            bus.invoke("LeaveBus", List.of(key));
        }
    }

    /** Subscribes to one event name. */
    public void on(String event, Consumer<BusEvent> handler) {
        handlers.computeIfAbsent(event, k -> new ArrayList<>()).add(handler);
    }

    /** Subscribes to every event on this bus, whatever its name. */
    public void onAny(Consumer<BusEvent> handler) {
        synchronized (anyHandlers) {
            anyHandlers.add(handler);
        }
    }

    /** Fires only when the topic has presence enabled. */
    public void onPeerJoined(Consumer<String> handler) {
        synchronized (joinedHandlers) {
            joinedHandlers.add(handler);
        }
    }

    public void onPeerLeft(Consumer<String> handler) {
        synchronized (leftHandlers) {
            leftHandlers.add(handler);
        }
    }

    /**
     * The server removed this connection from the bus, because the topic was disabled or re-scoped
     * while the socket was open. The SDK does not re-join: that would be arguing with a decision
     * the server has just made.
     */
    public void onEvicted(Runnable handler) {
        synchronized (evictedHandlers) {
            evictedHandlers.add(handler);
        }
    }

    void dispatch(BusEvent event) {
        List<Consumer<BusEvent>> targets = new ArrayList<>(
            handlers.getOrDefault(event.event(), List.of()));
        synchronized (anyHandlers) {
            targets.addAll(anyHandlers);
        }
        for (Consumer<BusEvent> handler : targets) {
            safely(() -> handler.accept(event));
        }
    }

    void dispatchPeer(boolean isJoin, String userId) {
        List<Consumer<String>> targets;
        List<Consumer<String>> source = isJoin ? joinedHandlers : leftHandlers;
        synchronized (source) {
            targets = List.copyOf(source);
        }
        for (Consumer<String> handler : targets) {
            safely(() -> handler.accept(userId));
        }
    }

    void dispatchEvicted() {
        wanted = false;
        joined = false;

        List<Runnable> targets;
        synchronized (evictedHandlers) {
            targets = List.copyOf(evictedHandlers);
        }
        for (Runnable handler : targets) {
            safely(handler);
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // A caller's handler must never kill the receive loop: one bad listener would take
            // every other channel down with it.
            PraxLog.warn("An Event Bus handler threw: " + e);
        }
    }
}
