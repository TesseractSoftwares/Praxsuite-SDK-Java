package com.tesseractsoftwares.praxsuite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared Praxsuite SDK conformance contract, in Java.
 *
 * <p>Every case here also exists in the Unity, Lua, TypeScript, .NET, Godot and Python suites. Each
 * one is here because getting it wrong produces silently wrong data rather than an error, and at
 * least one shipped SDK got it wrong.
 *
 * <p>Entirely offline: no workspace, no credentials, no network.
 */
class ConformanceTest {

    // Shape-accurate fakes, assembled from fragments so a secret scanner does not flag this file.
    private static final String FAKE_SECRET = "sk_live_" + "0123456789abcdef0123456789abcdef";
    private static final String FAKE_PUBLISHABLE = "pk_live_" + "fedcba9876543210fedcba9876543210";
    private static final String FAKE_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmMifQ.signaturehere";
    private static final String WS = "1eb92f32-d628-4656-8c64-cd0d43c9869d";

    @Nested
    @DisplayName("filters build the gateway wire shape")
    class FilterShapes {

        @Test
        @DisplayName("eq")
        void eq() {
            assertEquals(Map.of("field", "Score", "op", "eq", "value", 100),
                Filters.eq("Score", 100));
        }

        @Test
        @DisplayName("isNull compiles to the is operator; there is no isNull operator")
        void isNullCompiles() {
            Map<String, Object> c = Filters.isNull("DeletedAt");
            assertEquals("is", c.get("op"));
            assertNull(c.get("value"));
            assertTrue(c.containsKey("value"), "the null must be sent, not omitted");
        }

        @Test
        @DisplayName("isNotNull compiles to neq null")
        void isNotNullCompiles() {
            Map<String, Object> c = Filters.isNotNull("DeletedAt");
            assertEquals("neq", c.get("op"));
            assertNull(c.get("value"));
        }

        @Test
        @DisplayName("startsWith and endsWith compile to like with the wildcard applied")
        void prefixSuffix() {
            assertEquals("Sword%", Filters.startsWith("Name", "Sword").get("value"));
            assertEquals("like", Filters.startsWith("Name", "Sword").get("op"));
            assertEquals("%blade", Filters.endsWith("Name", "blade").get("value"));
        }

        @Test
        @DisplayName("in carries a list, and an empty in is refused")
        void inList() {
            assertEquals(List.of(1, 2, 3), Filters.in("Level", List.of(1, 2, 3)).get("value"));
            // An empty IN matches nothing, silently. Refusing beats a query that returns zero rows
            // for a reason nobody can see.
            assertThrows(PraxValidationError.class, () -> Filters.in("Level", List.of()));
        }

        @Test
        @DisplayName("between carries exactly two values")
        void between() {
            assertEquals(List.of(10, 20), Filters.between("Score", 10, 20).get("value"));
        }

        @Test
        @DisplayName("groups nest under or / and")
        void groups() {
            Map<String, Object> or = Filters.anyOf(
                Filters.eq("Rarity", "legendary"), Filters.eq("Rarity", "epic"));
            assertInstanceOf(List.class, or.get("or"));
            assertEquals(2, ((List<?>) or.get("or")).size());

            Map<String, Object> and = Filters.allOf(
                Filters.gte("Level", 5), Filters.lte("Level", 10));
            assertEquals(2, ((List<?>) and.get("and")).size());
        }

        @Test
        @DisplayName("a blank column name is refused")
        void blankColumn() {
            assertThrows(PraxValidationError.class, () -> Filters.eq("   ", 1));
        }

        @Test
        @DisplayName("nothing here can emit an operator the gateway lacks")
        void onlyRealOperators() {
            List<Map<String, Object>> all = List.of(
                Filters.eq("c", 1), Filters.neq("c", 1), Filters.gt("c", 1), Filters.gte("c", 1),
                Filters.lt("c", 1), Filters.lte("c", 1), Filters.like("c", "x"),
                Filters.ilike("c", "x"), Filters.contains("c", "x"), Filters.textSearch("c", "x"),
                Filters.startsWith("c", "x"), Filters.endsWith("c", "x"), Filters.isNull("c"),
                Filters.isNotNull("c"), Filters.in("c", List.of(1)), Filters.between("c", 1, 2));
            for (Map<String, Object> c : all) {
                assertTrue(Filters.SUPPORTED_OPERATORS.contains(c.get("op")),
                    "unsupported operator leaked: " + c.get("op"));
            }
            assertEquals(13, Filters.SUPPORTED_OPERATORS.size());
        }
    }

    @Nested
    @DisplayName("result parsing")
    class Parsing {

        @Test
        @DisplayName("a page reads meta.total, never meta.totalCount")
        void readsTotal() {
            // Reading the wrong name returns nothing and reports zero, silently, forever. That
            // exact mistake shipped in another SDK and went unnoticed for months.
            Responses.Page page = Responses.parsePage(Map.of(
                "data", List.of(Map.of("ID", "a"), Map.of("ID", "b")),
                "meta", Map.of("limit", 50, "offset", 0, "count", 2, "total", 137,
                    "durationMs", 12)));
            assertEquals(137L, page.total());
            assertEquals(2, page.size());
            assertEquals(50, page.limit());
            assertEquals(12, page.durationMs());
            assertTrue(page.hasMore());
        }

        @Test
        @DisplayName("an absent total is null, not zero")
        void absentTotalIsNull() {
            // "No rows matched" must stay distinguishable from "nobody asked for a count".
            Responses.Page page = Responses.parsePage(Map.of(
                "data", List.of(), "meta", Map.of("limit", 50, "count", 0)));
            assertNull(page.total());
            assertTrue(page.isEmpty());
        }

        @Test
        @DisplayName("meta.totalCount is ignored, because it does not exist")
        void totalCountIsIgnored() {
            Responses.Page page = Responses.parsePage(Map.of(
                "data", List.of(), "meta", Map.of("totalCount", 99)));
            assertNull(page.total());
        }

        @Test
        @DisplayName("a mutation reports affected rows and the returned row")
        void mutation() {
            Responses.MutationResult m = Responses.parseMutation(Map.of(
                "affectedRows", 1, "data", List.of(Map.of("ID", "new")),
                "meta", Map.of("durationMs", 8)));
            assertEquals(1, m.affectedRows());
            assertEquals(Map.of("ID", "new"), m.row());
        }

        @Test
        @DisplayName("the auth envelope is unwrapped")
        void authEnvelopeUnwrapped() {
            assertEquals(Map.of("accessToken", "a.b.c"),
                Responses.unwrapEnvelope(Map.of("isSuccess", true,
                    "data", Map.of("accessToken", "a.b.c"))));
        }

        @Test
        @DisplayName("a query body is NOT unwrapped, because its data is a list")
        void queryBodyNotUnwrapped() {
            Map<String, Object> body = Map.of("data", List.of(Map.of("ID", "x")),
                "meta", Map.of("count", 1));
            assertTrue(Responses.unwrapEnvelope(body).containsKey("meta"));
        }
    }

    @Nested
    @DisplayName("error shapes and classification")
    class Errors {

        @Test
        @DisplayName("the query error object shape")
        void queryErrorObject() {
            PraxError e = Responses.parseError(403,
                "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Read access denied.\","
                    + "\"details\":[\"scope\"]}}");
            assertEquals("FORBIDDEN", e.code());
            assertTrue(e.isForbidden());
            assertFalse(e.isTransient());
            assertEquals(List.of("scope"), e.details());
            assertInstanceOf(PraxForbiddenError.class, e);
        }

        @Test
        @DisplayName("the files and endpoint error shape is a bare string")
        void bareStringError() {
            PraxError e = Responses.parseError(400, "{\"error\":\"File type not allowed.\"}");
            assertEquals("File type not allowed.", e.getMessage());
        }

        @Test
        @DisplayName("a non-JSON body does not break the parser")
        void nonJsonBody() {
            PraxError e = Responses.parseError(502, "<html>Bad Gateway</html>");
            assertTrue(e.getMessage().contains("Bad Gateway"));
            assertEquals("HTTP_502", e.code());
        }

        @Test
        @DisplayName("an empty body still explains itself")
        void emptyBodyStillExplains() {
            assertFalse(Responses.parseError(500, "").getMessage().isBlank());
        }

        @Test
        @DisplayName("rate limit and quota share 429 and classify oppositely")
        void rateLimitVersusQuota() {
            PraxError rate = PraxError.of("RATE_LIMIT_EXCEEDED", "", 429, List.of(), "");
            PraxError quota = PraxError.of("QUOTA_EXCEEDED", "", 429, List.of(), "");
            assertTrue(rate.isTransient());
            assertInstanceOf(PraxRateLimitError.class, rate);
            assertFalse(quota.isTransient());
            assertTrue(quota.isQuotaExceeded());
            assertInstanceOf(PraxQuotaExceededError.class, quota);
            assertTrue(PraxError.of("EGRESS_LIMIT_EXCEEDED", "", 429, List.of(), "")
                .isQuotaExceeded());
        }

        @Test
        @DisplayName("transient classification")
        void transientClassification() {
            assertTrue(PraxError.of("NETWORK_ERROR", "", 0, List.of(), "").isTransient());
            assertTrue(PraxError.of("HTTP_503", "", 503, List.of(), "").isTransient());
            assertFalse(PraxError.of("FORBIDDEN", "", 403, List.of(), "").isTransient());
        }

        @Test
        @DisplayName("a timeout is a network error, so one catch covers both")
        void timeoutIsANetworkError() {
            PraxError t = PraxError.of("TIMEOUT", "", 0, List.of(), "");
            assertInstanceOf(PraxNetworkError.class, t);
            assertTrue(t.isNetworkError());
        }

        @Test
        @DisplayName("every SDK error is catchable as one type")
        void oneCatchCoversEverything() {
            assertInstanceOf(PraxError.class, new PraxValidationError("X", "y"));
            assertInstanceOf(RuntimeException.class, new PraxError("X", "y"));
        }
    }

    @Nested
    @DisplayName("credential handling")
    class Credentials {

        @Test
        @DisplayName("classification by shape")
        void classification() {
            assertEquals(KeyGuard.Kind.SECRET, KeyGuard.classify(FAKE_SECRET));
            assertEquals(KeyGuard.Kind.PUBLISHABLE, KeyGuard.classify(FAKE_PUBLISHABLE));
            assertEquals(KeyGuard.Kind.JWT, KeyGuard.classify(FAKE_JWT));
            assertEquals(KeyGuard.Kind.UNKNOWN, KeyGuard.classify(""));
            assertEquals(KeyGuard.Kind.UNKNOWN, KeyGuard.classify(null));
        }

        @Test
        @DisplayName("a secret key is refused where it would be exposed")
        void secretRefusedClientSide() {
            PraxValidationError e = assertThrows(PraxValidationError.class,
                () -> KeyGuard.requireClientSafe(FAKE_SECRET, "a test"));
            assertEquals("SECRET_KEY_REFUSED", e.code());
        }

        @Test
        @DisplayName("publishable keys and session tokens are accepted")
        void publishableAccepted() {
            assertDoesNotThrow(() -> KeyGuard.requireClientSafe(FAKE_PUBLISHABLE, "a test"));
            assertDoesNotThrow(() -> KeyGuard.requireClientSafe(FAKE_JWT, "a test"));
            assertDoesNotThrow(() -> KeyGuard.requireClientSafe(null, "a test"));
        }

        @Test
        @DisplayName("redaction keeps the prefix and hides the material")
        void redaction() {
            String masked = KeyGuard.redact(FAKE_SECRET);
            assertTrue(masked.startsWith("sk_live_"));
            assertFalse(masked.contains("0123456789abcdef"));
        }

        @Test
        @DisplayName("scrubbing removes every credential shape")
        void scrubbing() {
            String text = "key=" + FAKE_SECRET + " pub=" + FAKE_PUBLISHABLE + " jwt=" + FAKE_JWT
                + " {\"refreshToken\":\"rt-secret-value\",\"password\":\"hunter2\"}";
            String cleaned = PraxLog.scrub(text);
            assertFalse(cleaned.contains("0123456789abcdef"));
            assertFalse(cleaned.contains("fedcba9876543210"));
            assertFalse(cleaned.contains("signaturehere"));
            assertFalse(cleaned.contains("rt-secret-value"));
            assertFalse(cleaned.contains("hunter2"));
            // The prefix survives, so a log still says which kind of key was involved.
            assertTrue(cleaned.contains("sk_live_"));
            assertTrue(cleaned.contains("pk_live_"));
        }
    }

    @Nested
    @DisplayName("routes")
    class RouteShapes {

        @Test
        @DisplayName("query uses the FrontDoor short form")
        void queryRoute() {
            assertEquals("https://gateway.praxsuite.com/" + WS + "/query",
                Routes.query("https://gateway.praxsuite.com", WS));
        }

        @Test
        @DisplayName("auth actions nest under auth/")
        void authRoute() {
            assertTrue(Routes.auth("https://gateway.praxsuite.com", WS, "login")
                .endsWith("/" + WS + "/auth/login"));
        }

        @Test
        @DisplayName("a trailing slash and a missing scheme are normalised")
        void normalisation() {
            assertEquals("https://gateway.praxsuite.com/" + WS + "/schema",
                Routes.schema("gateway.praxsuite.com/", WS));
            assertEquals(Routes.CLOUD_HOST, Routes.normalizeBaseUrl(""));
            assertEquals(Routes.CLOUD_HOST, Routes.normalizeBaseUrl(null));
        }

        @Test
        @DisplayName("an endpoint id is escaped so it cannot walk out of its path segment")
        void endpointEscaped() {
            assertFalse(Routes.endpoint("https://g.example.com", WS, "a/../b").contains("/../"));
        }

        @Test
        @DisplayName("plaintext remote is flagged, loopback is not")
        void insecureRemote() {
            assertTrue(Routes.isInsecureRemote("http://gateway.example.com"));
            assertFalse(Routes.isInsecureRemote("https://gateway.example.com"));
            assertFalse(Routes.isInsecureRemote("http://localhost:5049"));
            assertFalse(Routes.isInsecureRemote("http://127.0.0.1:5049"));
        }
    }
}
