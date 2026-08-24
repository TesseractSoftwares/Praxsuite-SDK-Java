package com.tesseractsoftwares.praxsuite.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer.
 *
 * <p>Java has no JSON in its standard library, so this SDK carries its own rather than depending on
 * Gson or Jackson. That is a deliberate trade for the audience: a Paper/Spigot plugin runs inside a
 * server classloader that already holds its own Gson, and two version-skewed copies of a shaded
 * library is one of the classic ways a plugin breaks a server it did not ship with. Zero
 * dependencies removes the question.
 *
 * <p>The scope is exactly what the gateway needs, and no more. It reads and writes the JSON the
 * gateway actually produces and consumes. It is not a general-purpose library: there is no
 * reflection, no annotations, no object mapping. Values come back as {@code Map<String,Object>},
 * {@code List<Object>}, {@code String}, {@code Double}, {@code Long}, {@code Boolean} or
 * {@code null}.
 *
 * <p>Numbers deserve a note. A JSON number becomes a {@code Long} when it has no fraction or
 * exponent and fits, and a {@code Double} otherwise. That matters because Godot's JSON turns every
 * number into a float and Python's keeps ints - an SDK that silently made every Int column a
 * double would be a third behaviour again. An Int column arrives here as a {@code Long}.
 */
public final class Json {

    private Json() {}

    // ── writing ─────────────────────────────────────────────────────────────

    /** Serialises a value to JSON. Accepts Map, List, String, Number, Boolean and null. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(n, out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                writeValue(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                writeValue(item, out);
            }
            out.append(']');
        } else if (value instanceof Object[] items) {
            writeValue(List.of(items), out);
        } else {
            // Anything else would be silently wrong on the wire. Better to say so here than to
            // send "com.example.Thing@1a2b3c" to the gateway and debug the 400 later.
            throw new IllegalArgumentException(
                "Cannot serialise " + value.getClass().getName()
                    + " to JSON. Pass a Map, List, String, Number, Boolean or null.");
        }
    }

    private static void writeNumber(Number n, StringBuilder out) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                // JSON has no NaN or Infinity. Emitting them produces a body the gateway rejects
                // with a parse error that says nothing about which field caused it.
                throw new IllegalArgumentException(
                    "JSON cannot represent " + d + ". Check the value before sending it.");
            }
            // Avoid the trailing ".0" on whole doubles: an Int column sent as 3.0 can be rejected.
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else {
            out.append(n);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        // Emitted as-is, including astral characters, which arrive here as a
                        // surrogate pair and pass through unchanged. Escaping them to \\uXXXX
                        // would also be valid JSON, but emoji in a display name are constant in
                        // practice and the unescaped form is what every other Praxsuite SDK sends.
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /**
     * Parses JSON text.
     *
     * @throws JsonException if the text is not valid JSON.
     */
    public static Object read(String text) {
        if (text == null) throw new JsonException("No JSON to parse.");
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("Unexpected trailing content at position " + p.index());
        }
        return value;
    }

    /** Parses JSON, returning null instead of throwing. For bodies that may not be JSON at all. */
    public static Object readOrNull(String text) {
        try {
            return read(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses JSON expected to be an object. Returns an empty map for anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object parsed = readOrNull(text);
        return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
    }

    /** Thrown when JSON cannot be parsed. */
    public static final class JsonException extends RuntimeException {
        // Every Throwable is Serializable, so a missing serialVersionUID makes the serialised form
        // depend on the compiler. Pinned rather than generated.
        private static final long serialVersionUID = 1L;

        public JsonException(String message) { super(message); }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        int index() { return i; }
        boolean atEnd() { return i >= s.length(); }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object readValue() {
            if (atEnd()) throw new JsonException("Unexpected end of JSON.");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> readObjectValue();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObjectValue() {
            // LinkedHashMap so a round trip keeps field order. It makes a logged body diffable
            // against the request that produced it.
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new JsonException("Expected ',' or '}' at position " + (i - 1));
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { i++; return list; }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new JsonException("Expected ',' or ']' at position " + (i - 1));
            }
        }

        String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("Unterminated string.");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }

                if (atEnd()) throw new JsonException("Unterminated escape.");
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"'  -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/'  -> out.append('/');
                    case 'n'  -> out.append('\n');
                    case 'r'  -> out.append('\r');
                    case 't'  -> out.append('\t');
                    case 'b'  -> out.append('\b');
                    case 'f'  -> out.append('\f');
                    case 'u'  -> {
                        if (i + 4 > s.length()) throw new JsonException("Truncated \\u escape.");
                        // Appended as a raw char, so a surrogate PAIR written as two \\u escapes
                        // reassembles into one astral character for free. Decoding each half into
                        // its own String would corrupt every emoji the gateway sends.
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new JsonException("Invalid escape \\" + esc);
                }
            }
        }

        Object readBoolean() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new JsonException("Invalid literal at position " + i);
        }

        Object readNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new JsonException("Invalid literal at position " + i);
        }

        Object readNumber() {
            int start = i;
            if (peek() == '-' || peek() == '+') i++;
            boolean fractional = false;
            while (!atEnd()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { i++; }
                else if (c == '.' || c == 'e' || c == 'E') { fractional = true; i++; }
                else if ((c == '-' || c == '+') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')) { i++; }
                else break;
            }
            String raw = s.substring(start, i);
            if (raw.isEmpty() || raw.equals("-")) {
                throw new JsonException("Invalid number at position " + start);
            }
            if (!fractional) {
                try {
                    // A whole number stays a Long. An Int column must not arrive as a double -
                    // Godot's JSON does that and it forces a cast at every call site.
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    // Larger than a long; fall through to double rather than failing the parse.
                }
            }
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number \"" + raw + "\" at position " + start);
            }
        }

        char peek() {
            if (atEnd()) throw new JsonException("Unexpected end of JSON.");
            return s.charAt(i);
        }

        char next() {
            if (atEnd()) throw new JsonException("Unexpected end of JSON.");
            return s.charAt(i++);
        }

        void expect(char c) {
            if (next() != c) throw new JsonException("Expected '" + c + "' at position " + (i - 1));
        }
    }
}
