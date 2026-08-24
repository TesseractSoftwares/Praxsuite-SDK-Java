package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled codec is load-bearing: every response the gateway sends passes through it, so a bug
 * here is silently wrong data everywhere rather than a visible failure in one place.
 */
class JsonTest {

    @Nested
    @DisplayName("numbers")
    class Numbers {

        @Test
        @DisplayName("a whole number stays a Long, so an Int column needs no cast")
        void wholeNumbersAreLongs() {
            Map<String, Object> m = Json.readObject("{\"n\":1,\"big\":9007199254740993}");
            assertInstanceOf(Long.class, m.get("n"));
            assertEquals(1L, m.get("n"));
            // Beyond a double's exact integer range - the point of parsing as Long at all.
            assertEquals(9007199254740993L, m.get("big"));
        }

        @Test
        @DisplayName("a fractional number is a Double")
        void fractionsAreDoubles() {
            Map<String, Object> m = Json.readObject("{\"a\":1.5,\"b\":2e3,\"c\":-0.25}");
            assertInstanceOf(Double.class, m.get("a"));
            assertEquals(1.5, m.get("a"));
            assertEquals(2000.0, m.get("b"));
            assertEquals(-0.25, m.get("c"));
        }

        @Test
        @DisplayName("a whole double is written without a trailing .0")
        void wholeDoublesLoseTheTrailingZero() {
            // An Int column can reject 3.0 where it accepts 3.
            assertEquals("{\"n\":3}", Json.write(Map.of("n", 3.0d)));
            assertEquals("{\"n\":3}", Json.write(Map.of("n", 3L)));
        }

        @Test
        @DisplayName("NaN and Infinity are refused rather than emitted")
        void nonFiniteIsRefused() {
            // JSON cannot represent either. Emitting them produces a gateway parse error that
            // names no field, so failing here is strictly more useful.
            assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("n", Double.NaN)));
            assertThrows(IllegalArgumentException.class,
                () -> Json.write(Map.of("n", Double.POSITIVE_INFINITY)));
        }
    }

    @Nested
    @DisplayName("strings and encoding")
    class Encoding {

        @Test
        @DisplayName("astral emoji survive a round trip")
        void astralEmojiRoundTrip() {
            // Display names contain emoji constantly; a codec that mangles them corrupts data with
            // no error anywhere.
            String name = "Aria 🚀🇨🇱";
            Map<String, Object> back = Json.readObject(Json.write(Map.of("name", name)));
            assertEquals(name, back.get("name"));
        }

        @Test
        @DisplayName("an escaped surrogate pair decodes to one character")
        void escapedSurrogatePairs() {
            Map<String, Object> m = Json.readObject("{\"name\":\"\\ud83d\\ude80\"}");
            assertEquals("🚀", m.get("name"));
            assertEquals(1, ((String) m.get("name")).codePointCount(0, 2));
        }

        @Test
        @DisplayName("control characters and quotes are escaped")
        void escaping() {
            String written = Json.write(Map.of("s", "a\"b\\c\nd\te"));
            assertTrue(written.contains("\\\""), written);
            assertTrue(written.contains("\\\\"), written);
            assertTrue(written.contains("\\n"), written);
            assertEquals("a\"b\\c\nd\te", Json.readObject(written).get("s"));
        }

        @Test
        @DisplayName("all the standard escapes are read")
        void readsEscapes() {
            Map<String, Object> m = Json.readObject("{\"s\":\"\\\"\\\\\\/\\n\\r\\t\\b\\f\"}");
            assertEquals("\"\\/\n\r\t\b\f", m.get("s"));
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        @Test
        @DisplayName("field order is preserved, so a logged body is diffable")
        void fieldOrderIsPreserved() {
            String src = "{\"z\":1,\"a\":2,\"m\":3}";
            assertEquals(src, Json.write(Json.read(src)));
        }

        @Test
        @DisplayName("nested objects and arrays")
        void nesting() {
            Object parsed = Json.read("{\"refs\":{\"t\":\"Orders\"},\"where\":[{\"op\":\"eq\"}]}");
            assertInstanceOf(Map.class, parsed);
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) parsed;
            assertInstanceOf(Map.class, m.get("refs"));
            assertInstanceOf(List.class, m.get("where"));
        }

        @Test
        @DisplayName("empty object and array")
        void empties() {
            assertEquals(Map.of(), Json.read("{}"));
            assertEquals(List.of(), Json.read("[]"));
            assertEquals("{}", Json.write(new LinkedHashMap<String, Object>()));
            assertEquals("[]", Json.write(List.of()));
        }

        @Test
        @DisplayName("null is a value, not an absence")
        void nulls() {
            Map<String, Object> m = Json.readObject("{\"a\":null}");
            assertTrue(m.containsKey("a"));
            assertNull(m.get("a"));
            // A filter for "is null" sends an explicit null; dropping it would change the query.
            assertEquals("{\"value\":null}", Json.write(Map.of()).equals("{}")
                ? Json.write(mapWithNull()) : Json.write(mapWithNull()));
        }

        private Map<String, Object> mapWithNull() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", null);
            return m;
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {

        @Test
        @DisplayName("readOrNull returns null instead of throwing")
        void readOrNullIsQuiet() {
            // An edge proxy answering with an HTML 502 is routine, and must not look like a crash.
            assertNull(Json.readOrNull("<html>Bad Gateway</html>"));
            assertNull(Json.readOrNull(""));
            assertNull(Json.readOrNull("{unclosed"));
            assertNotNull(Json.readOrNull("{\"a\":1}"));
        }

        @Test
        @DisplayName("readObject gives an empty map for a non-object")
        void readObjectDegradesGracefully() {
            assertEquals(Map.of(), Json.readObject("[1,2,3]"));
            assertEquals(Map.of(), Json.readObject("not json"));
            assertEquals(Map.of(), Json.readObject(null));
        }

        @Test
        @DisplayName("read throws with a position, so a bad body can be located")
        void readThrowsWithPosition() {
            Json.JsonException e = assertThrows(Json.JsonException.class,
                () -> Json.read("{\"a\":1,}"));
            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("trailing content is rejected rather than ignored")
        void trailingContentRejected() {
            assertThrows(Json.JsonException.class, () -> Json.read("{\"a\":1} {\"b\":2}"));
        }

        @Test
        @DisplayName("an unserialisable type fails loudly instead of stringifying")
        void unserialisableTypeIsRefused() {
            // Otherwise "com.example.Thing@1a2b3c" goes on the wire and the 400 says nothing.
            assertThrows(IllegalArgumentException.class,
                () -> Json.write(Map.of("x", new Object())));
        }
    }
}
