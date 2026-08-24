package com.tesseractsoftwares.praxsuite;

import com.tesseractsoftwares.praxsuite.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the gateway's response shapes.
 *
 * <p>There is not one envelope. An SDK that assumes there is will mis-parse two of the three:
 *
 * <ul>
 *   <li>{@code POST /{ws}/query} - the body IS the result: {@code {"data": [...], "meta": {...}}}
 *   <li>{@code POST /{ws}/auth/*} - platform envelope: the payload is under {@code .data}
 *   <li>{@code /{ws}/files/*} and {@code /{ws}/endpoint/*} - errors are
 *       {@code {"error": "a bare string"}}, not an object
 * </ul>
 */
public final class Responses {

    private Responses() {}

    /** One page of rows plus the metadata the gateway returned with it. */
    public static final class Page {
        private final List<Map<String, Object>> rows;
        private final Long total;
        private final int limit;
        private final int offset;
        private final int count;
        private final long durationMs;

        Page(List<Map<String, Object>> rows, Long total, int limit, int offset,
             int count, long durationMs) {
            this.rows = List.copyOf(rows);
            this.total = total;
            this.limit = limit;
            this.offset = offset;
            this.count = count;
            this.durationMs = durationMs;
        }

        public List<Map<String, Object>> rows() { return rows; }

        /**
         * Total matching rows ignoring limit and offset, or null when it was not requested.
         *
         * <p>Null rather than 0 deliberately: "no rows matched" must stay distinguishable from
         * "nobody asked for a count".
         */
        public Long total() { return total; }

        /**
         * The limit the gateway ACTUALLY applied, after clamping to the table scope's maximum.
         * Read this rather than assuming your requested limit was honoured.
         */
        public int limit() { return limit; }

        public int offset() { return offset; }
        public int count() { return count; }
        public long durationMs() { return durationMs; }
        public boolean isEmpty() { return rows.isEmpty(); }
        public int size() { return rows.size(); }

        /** The first row, or null when nothing matched. */
        public Map<String, Object> first() { return rows.isEmpty() ? null : rows.get(0); }

        /** True when another page exists. */
        public boolean hasMore() {
            if (total != null) return offset + rows.size() < total;
            // With no total requested, a full page is the only available hint.
            return limit > 0 && rows.size() >= limit;
        }
    }

    /** The outcome of an insert, update or delete. */
    public static final class MutationResult {
        private final long affectedRows;
        private final List<Map<String, Object>> rows;
        private final long durationMs;

        MutationResult(long affectedRows, List<Map<String, Object>> rows, long durationMs) {
            this.affectedRows = affectedRows;
            this.rows = List.copyOf(rows);
            this.durationMs = durationMs;
        }

        public long affectedRows() { return affectedRows; }
        public List<Map<String, Object>> rows() { return rows; }
        public long durationMs() { return durationMs; }

        /** The single affected row, for the common one-row case. */
        public Map<String, Object> row() { return rows.isEmpty() ? null : rows.get(0); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static long asLong(Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    /** Reads a {@code /query} select response. */
    public static Page parsePage(Map<String, Object> body) {
        Map<String, Object> meta = asMap(body.get("meta"));
        List<Map<String, Object>> rows = asRows(body.get("data"));

        // meta.total, NEVER meta.totalCount. Reading the wrong name returns nothing and reports
        // zero, silently, forever - one SDK shipped that for months before anyone noticed.
        Object rawTotal = meta.get("total");
        Long total = rawTotal == null ? null : asLong(rawTotal, 0L);

        return new Page(
            rows,
            total,
            (int) asLong(meta.get("limit"), 0),
            (int) asLong(meta.get("offset"), 0),
            (int) asLong(meta.get("count"), rows.size()),
            asLong(meta.get("durationMs"), 0));
    }

    /** Reads a {@code /query} mutation response. */
    public static MutationResult parseMutation(Map<String, Object> body) {
        Map<String, Object> meta = asMap(body.get("meta"));
        return new MutationResult(
            asLong(body.get("affectedRows"), 0),
            asRows(body.get("data")),
            asLong(meta.get("durationMs"), 0));
    }

    /**
     * Unwraps the platform envelope used by {@code /auth/*}.
     *
     * <p>A {@code /query} body also has a {@code data} key, but it is a LIST - checking the type is
     * what makes this safe to call on either shape. Do NOT call it on an endpoint response: those
     * are the automation's own payload, and a top-level {@code data} object in one is ordinary.
     */
    public static Map<String, Object> unwrapEnvelope(Map<String, Object> body) {
        Object inner = body.get("data");
        return inner instanceof Map ? asMap(inner) : body;
    }

    /** Builds a typed error from a non-2xx body, handling all the shapes above. */
    public static PraxError parseError(int status, String rawBody) {
        String code = "";
        String message = "";
        List<String> details = new ArrayList<>();

        if (rawBody != null && !rawBody.isBlank()) {
            Object parsed = Json.readOrNull(rawBody);
            if (parsed instanceof Map) {
                Map<String, Object> body = asMap(parsed);
                Object err = body.get("error");
                if (err instanceof Map) {
                    Map<String, Object> e = asMap(err);
                    code = String.valueOf(e.getOrDefault("code", ""));
                    message = String.valueOf(e.getOrDefault("message", ""));
                    if (e.get("details") instanceof List<?> list) {
                        for (Object d : list) details.add(String.valueOf(d));
                    }
                } else if (err instanceof String s) {
                    // The /files and /endpoint routes report a bare string here, not an object.
                    message = s;
                } else {
                    message = String.valueOf(body.getOrDefault("message", ""));
                    if (body.get("errors") instanceof List<?> list) {
                        for (Object d : list) details.add(String.valueOf(d));
                    }
                }
            } else {
                // Not JSON at all - an HTML error page from an edge proxy, most likely.
                message = rawBody.length() > 400 ? rawBody.substring(0, 400) : rawBody;
            }
        }

        if (code == null || code.isEmpty()) code = "HTTP_" + status;
        if (message == null || message.isEmpty() || "null".equals(message)) {
            message = describeStatus(status);
        }
        return PraxError.of(code, message, status, details, rawBody == null ? "" : rawBody);
    }

    private static String describeStatus(int status) {
        return switch (status) {
            case 400 -> "The gateway rejected the request as malformed.";
            case 401 -> "Not authenticated. The API key or session token is missing, expired, or "
                        + "does not belong to this workspace.";
            case 403 -> "Not authorised. This credential or role is not scoped for that table or "
                        + "operation.";
            case 404 -> "Not found. Check the workspace id, and that it exists on this gateway "
                        + "host - Praxsuite runs several independent tiers.";
            case 409 -> "Conflict. The row changed underneath you, or a unique value is taken.";
            case 413 -> "The request body is too large.";
            case 429 -> "Rate limited, or a plan allowance is exhausted. Check the error code to "
                        + "tell which - only one of them is worth retrying.";
            case 500 -> "The gateway failed to handle the request.";
            case 502 -> "The gateway is unreachable from the edge.";
            case 503 -> "The gateway is temporarily unavailable.";
            case 504 -> "The gateway timed out.";
            default -> "The gateway returned HTTP " + status + ".";
        };
    }
}
