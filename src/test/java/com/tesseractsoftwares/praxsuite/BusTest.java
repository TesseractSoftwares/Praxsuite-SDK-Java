package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Event Bus half of the shared conformance contract, in Java.
 *
 * <p>Every case was either measured against the live hub on 2026-09-07 or exists because getting it
 * wrong produces silently wrong behaviour rather than an exception.
 *
 * <p>Entirely offline: no workspace, no credentials, no socket.
 */
class BusTest {

    private static final String RS = "\u001e";
    private static final String USER = "729531eb-98ca-4cfd-bb79-452dbe177ca5";

    @Nested
    @DisplayName("bus keys")
    class BusKeys {

        @Test
        @DisplayName("the topic folds to lowercase and the instance does not")
        void foldsOnlyTheTopic() {
            // Fold the whole key and office:HQ merges with office:hq, two genuinely different
            // buses. Fold neither and two peers resolve the same topic, are both admitted, and
            // silently never see each other.
            assertEquals("office:HQ", BusWire.normalizeBusKey("Office:HQ"));
        }

        @Test
        void anAlreadyLowercaseKeyIsUnchanged() {
            assertEquals("channel:9f1c0f2e", BusWire.normalizeBusKey("channel:9f1c0f2e"));
        }

        @Test
        void aKeyWithoutAnInstanceStillFolds() {
            assertEquals("lobby", BusWire.normalizeBusKey("LOBBY"));
        }

        @Test
        void whitespaceIsTrimmed() {
            assertEquals("office:hq", BusWire.normalizeBusKey("  office:hq  "));
        }

        @Test
        void userSelfPassesThroughUntouched() {
            assertEquals("user:self", BusWire.normalizeBusKey("user:self"));
        }

        @Test
        void anEmptyKeyIsRejectedBeforeTheRoundTrip() {
            assertThrows(PraxValidationError.class, () -> BusWire.requireValidBusKey("   "));
        }

        @Test
        void aKeyContainingWsIsRejected() {
            assertThrows(PraxValidationError.class,
                () -> BusWire.requireValidBusKey("x:ws:something"));
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {

        @Test
        @DisplayName("the handshake is byte-exact")
        void handshakeIsExact() {
            assertEquals("{\"protocol\":\"json\",\"version\":1}", BusWire.HANDSHAKE_FRAME);
        }

        @Test
        void aSingleFrameSplitsToOneMessage() {
            BusWire.Frames split = BusWire.splitFrames("{\"type\":6}" + RS);
            assertEquals(1, split.frames().size());
            assertEquals("", split.remainder());
        }

        @Test
        @DisplayName("two coalesced frames split into two")
        void coalescedFramesSplit() {
            // One physical message can carry several frames. Parsing the whole buffer breaks
            // under exactly the load the bus exists for.
            BusWire.Frames split = BusWire.splitFrames(
                "{\"type\":6}" + RS + "{\"type\":3,\"invocationId\":\"1\",\"result\":null}" + RS);

            assertEquals(2, split.frames().size());
            assertEquals("{\"type\":6}", split.frames().get(0));
        }

        @Test
        void aTrailingPartialFrameIsBufferedNotParsed() {
            BusWire.Frames split =
                BusWire.splitFrames("{\"type\":6}" + RS + "{\"type\":3,\"invoca");

            assertEquals(1, split.frames().size());
            assertEquals("{\"type\":3,\"invoca", split.remainder());
        }

        @Test
        void anEmptySegmentIsDropped() {
            assertEquals(1, BusWire.splitFrames(RS + "{\"type\":6}" + RS).frames().size());
        }
    }

    @Nested
    @DisplayName("invocations")
    class Invocations {

        @Test
        @DisplayName("join sends an explicit null ticket")
        void joinSendsNullTicket() {
            List<Object> args = new ArrayList<>();
            args.add("office:hq");
            args.add(null);

            Map<String, Object> parsed =
                Json.readObject(BusWire.buildInvocation("1", "JoinBus", args));

            assertEquals("JoinBus", parsed.get("target"));
            assertEquals(List.of("office:hq"),
                ((List<?>) parsed.get("arguments")).stream().filter(java.util.Objects::nonNull)
                    .toList());
            assertEquals(2, ((List<?>) parsed.get("arguments")).size());
        }

        @Test
        @DisplayName("the invocation id is a string, not a number")
        void invocationIdIsAString() {
            // SignalR matches completions on it by value; a numeric id never matches.
            Map<String, Object> parsed =
                Json.readObject(BusWire.buildInvocation("5", "LeaveBus", List.of("x:y")));

            assertInstanceOf(String.class, parsed.get("invocationId"));
        }

        @Test
        void publishCarriesKeyEventAndPayloadInThatOrder() {
            Map<String, Object> parsed = Json.readObject(BusWire.buildInvocation(
                "3", "Publish", List.of("office:hq", "move", Map.of("x", 1))));

            List<?> args = (List<?>) parsed.get("arguments");
            assertEquals("office:hq", args.get(0));
            assertEquals("move", args.get(1));
        }
    }

    @Nested
    @DisplayName("completions")
    class Completions {

        @Test
        void aJoinCompletionCarriesRetainedPeers() {
            BusWire.BusResult result = BusWire.parseBusResult(Json.readObject(
                "{\"type\":3,\"invocationId\":\"1\",\"result\":{\"ok\":true,\"error\":null,"
                + "\"peers\":[{\"userId\":\"" + USER + "\",\"event\":\"move\","
                + "\"payload\":{\"x\":1,\"y\":2}}]}}"));

            assertTrue(result.ok());
            assertEquals(1, result.peers().size());
            assertEquals("move", result.peers().get(0).event());
        }

        @Test
        @DisplayName("a rejection arrives inside a SUCCESSFUL completion")
        void rejectionIsInsideSuccess() {
            // Not an exception and not an HTTP status. Code that only inspects SignalR's error
            // channel reports every denied join as a success.
            BusWire.BusResult result = BusWire.parseBusResult(Json.readObject(
                "{\"type\":3,\"invocationId\":\"3\",\"result\":{\"ok\":false,"
                + "\"error\":\"unknown_topic\",\"peers\":[]}}"));

            assertFalse(result.ok());
            assertEquals("unknown_topic", result.error());
            assertFalse(result.isTransportError());
        }

        @Test
        @DisplayName("zero recipients is success, not failure")
        void zeroRecipientsIsSuccess() {
            BusWire.BusResult result = BusWire.parseBusResult(Json.readObject(
                "{\"type\":3,\"invocationId\":\"2\",\"result\":{\"ok\":true,\"error\":null,"
                + "\"recipients\":0}}"));

            assertTrue(result.ok());
            assertEquals(0, result.recipients());
        }

        @Test
        @DisplayName("a void result (LeaveBus) is ok, not a null dereference")
        void voidResultIsOk() {
            BusWire.BusResult result = BusWire.parseBusResult(
                Json.readObject("{\"type\":3,\"invocationId\":\"7\",\"result\":null}"));

            assertTrue(result.ok());
            assertTrue(result.peers().isEmpty());
        }

        @Test
        void aHubFaultIsKeptApartFromAPolicyRejection() {
            BusWire.BusResult result = BusWire.parseBusResult(Json.readObject(
                "{\"type\":3,\"invocationId\":\"9\",\"error\":\"An unexpected error occurred.\"}"));

            assertFalse(result.ok());
            assertTrue(result.isTransportError());
        }

        @Test
        void everyHubErrorCodeHasASentenceWorthReading() {
            for (String code : List.of("invalid_bus_key", "unknown_topic", "denied",
                "invalid_ticket", "bus_full_or_too_many_buses", "not_a_member",
                "invalid_event_name", "payload_too_large", "rate_limited")) {
                assertTrue(BusWire.describeError(code).length() > 20, code);
            }
        }
    }

    @Nested
    @DisplayName("urls")
    class Urls {

        @Test
        @DisplayName("the hub has no workspace segment")
        void hubHasNoWorkspaceSegment() {
            // The workspace comes from the token; adding a segment 404s.
            assertEquals("https://gw.test/hubs/event-bus/negotiate?negotiateVersion=1",
                BusWire.negotiateUrl("https://gw.test"));
        }

        @Test
        void theSocketUrlUpgradesTheSchemeAndCarriesTheToken() {
            assertEquals("wss://gw.test/hubs/event-bus?access_token=abc.def",
                BusWire.socketUrl("https://gw.test", "abc.def"));
        }

        @Test
        void aPlaintextBaseUrlGivesAPlaintextSocketUrl() {
            assertTrue(BusWire.socketUrl("http://localhost:5000", "t").startsWith("ws://"));
        }
    }

    @Nested
    @DisplayName("channels")
    class Channels {

        private Praxsuite client() {
            return Praxsuite.builder()
                .workspaceId("1eb92f32-d628-4656-8c64-cd0d43c9869d")
                .credential("pk_live_" + "fedcba9876543210fedcba9876543210")
                .baseUrl("https://gateway.example.test")
                .build();
        }

        @Test
        @DisplayName("topic composes the key and returns the same channel object")
        void topicComposesTheKey() {
            Praxsuite prax = client();
            PraxChannel a = prax.bus().topic("Office").channel("hq");
            PraxChannel b = prax.bus().channel("office:hq");

            assertSame(a, b);
            assertEquals("office:hq", a.key());
            assertEquals("office", a.topic());
            assertEquals("hq", a.instance());
        }

        @Test
        void selfIsTheReservedUserBus() {
            assertEquals("user:self", client().bus().self().key());
        }

        @Test
        @DisplayName("the bus refuses to connect without a signed-in end user")
        void refusesWithoutASession() {
            // The hub authenticates with the session token, not the workspace key, so this must
            // fail with a sentence that says so rather than a 401 from a wasted round trip.
            PraxAuthError refused =
                assertThrows(PraxAuthError.class, () -> client().bus().connect());

            assertEquals("BUS_REQUIRES_SESSION", refused.code());
        }
    }
}
