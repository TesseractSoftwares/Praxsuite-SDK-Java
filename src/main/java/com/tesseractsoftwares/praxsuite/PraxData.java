package com.tesseractsoftwares.praxsuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes table rows. Reached through the client: {@code prax.data()}.
 *
 * <p>Every call is authorised twice on the server: the credential (or the signed-in user's role)
 * must be scoped to the table, and any row filter on that scope is applied on top of your
 * conditions. A client cannot widen either, which is why this class exposes no way to try.
 */
public final class PraxData {

    /** Columns the backend fills in and rejects if a client supplies them. */
    public static final Set<String> NATIVE_COLUMNS = Set.of(
        "ID", "CREATEDDATE", "CREATEDBY", "UPDATEDDATE", "UPDATEDBY", "POSITION");

    private final Praxsuite client;

    PraxData(Praxsuite client) {
        this.client = client;
    }

    /** Starts a query against a table, by name or id. */
    public Query table(String nameOrId) {
        return new Query(this, nameOrId);
    }

    // ── writes ──────────────────────────────────────────────────────────────

    /**
     * Inserts one row.
     *
     * <p>Do not set an ownership column yourself. A column carrying a DefaultValueTemplate is
     * stamped from the caller's verified token and the gateway rejects a request that supplies it -
     * that rejection is the anti-tamper guarantee, so working around it defeats the isolation.
     */
    public Responses.MutationResult insert(String table, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "insert() needs at least one column to set.");
        }
        rejectNative(values.keySet(), "insert");
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("type", "insert");
        mutation.put("table", Query.ROOT);
        mutation.put("values", List.of(new LinkedHashMap<>(values)));
        mutation.put("returning", Boolean.TRUE);
        return mutate(table, mutation);
    }

    /** Inserts several rows in one request. */
    public Responses.MutationResult insertMany(String table, List<Map<String, Object>> rows) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                if (r != null && !r.isEmpty()) values.add(new LinkedHashMap<>(r));
            }
        }
        if (values.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "insertMany() needs at least one non-empty row.");
        }
        for (Map<String, Object> r : values) rejectNative(r.keySet(), "insert");

        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("type", "insert");
        mutation.put("table", Query.ROOT);
        mutation.put("values", values);
        mutation.put("returning", Boolean.TRUE);
        return mutate(table, mutation);
    }

    /**
     * Updates every row matching {@code conditions}.
     *
     * <p>The conditions are mandatory and positional, so an unscoped update cannot be written by
     * accident. The gateway rejects one anyway; refusing here means the mistake surfaces while you
     * are writing the code rather than as a 400 in production.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")  // the array is only read
    public final Responses.MutationResult update(String table, Map<String, Object> values,
                                                 Map<String, Object>... conditions) {
        return update(table, values,
            conditions == null ? List.<Map<String, Object>>of() : Arrays.asList(conditions));
    }

    /** Updates every row matching {@code conditions}. List form, for a set built at runtime. */
    public Responses.MutationResult update(String table, Map<String, Object> values,
                                           List<Map<String, Object>> conditions) {
        if (values == null || values.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "update() needs at least one column to set.");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new PraxValidationError("UNSCOPED_MUTATION",
                "update() requires conditions. An update with no WHERE would target every row you "
                    + "can reach; pass filters, or use updateById().");
        }
        rejectNative(values.keySet(), "update");

        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("type", "update");
        mutation.put("table", Query.ROOT);
        mutation.put("set", new LinkedHashMap<>(values));
        mutation.put("where", new ArrayList<>(conditions));
        return mutate(table, mutation);
    }

    public Responses.MutationResult updateById(String table, String rowId,
                                               Map<String, Object> values) {
        if (rowId == null || rowId.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "updateById() needs a row id.");
        }
        return update(table, values, List.of(Filters.eq("ID", rowId.trim())));
    }

    /** Deletes every row matching {@code conditions}. Mandatory, same reason as update. */
    @SafeVarargs
    @SuppressWarnings("varargs")  // the array is only read
    public final Responses.MutationResult delete(String table,
                                                 Map<String, Object>... conditions) {
        return delete(table,
            conditions == null ? List.<Map<String, Object>>of() : Arrays.asList(conditions));
    }

    /** Deletes every row matching {@code conditions}. List form. */
    public Responses.MutationResult delete(String table, List<Map<String, Object>> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new PraxValidationError("UNSCOPED_MUTATION",
                "delete() requires conditions. A delete with no WHERE would remove every row you "
                    + "can reach; pass filters, or use deleteById().");
        }
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("type", "delete");
        mutation.put("table", Query.ROOT);
        mutation.put("where", new ArrayList<>(conditions));
        return mutate(table, mutation);
    }

    public Responses.MutationResult deleteById(String table, String rowId) {
        if (rowId == null || rowId.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "deleteById() needs a row id.");
        }
        return delete(table, List.of(Filters.eq("ID", rowId.trim())));
    }

    /** Updates when {@code rowId} is given, inserts otherwise. */
    public Responses.MutationResult upsert(String table, Map<String, Object> values, String rowId) {
        if (rowId != null && !rowId.isBlank()) return updateById(table, rowId, values);
        return insert(table, values);
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** Sends a hand-built PraxQL request. The escape hatch for shapes the builder does not cover. */
    public Map<String, Object> execute(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST", "A request body is required.");
        }
        String url = Routes.query(client.baseUrl(), client.workspaceId());
        // Reads are safe to retry; a mutation is not.
        boolean retrySafe = !request.containsKey("mutation");
        return client.send("POST", url, request, retrySafe, null);
    }

    /**
     * Resolves a table name to whatever the gateway addresses it by.
     *
     * <p>A seam, so a future name-to-id lookup does not change every call site. Note the TypeScript
     * SDK does resolve names to ids through the schema; this one passes the name through, which the
     * gateway accepts.
     */
    String resolveTable(String nameOrId) {
        return nameOrId.trim();
    }

    private Responses.MutationResult mutate(String table, Map<String, Object> mutation) {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put(Query.ROOT, resolveTable(table));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("refs", refs);
        request.put("mutation", mutation);
        return Responses.parseMutation(execute(request));
    }

    private static void rejectNative(Iterable<String> keys, String verb) {
        List<String> offending = new ArrayList<>();
        for (String k : keys) {
            if (k != null && NATIVE_COLUMNS.contains(k.toUpperCase())) offending.add(k);
        }
        if (!offending.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "The backend maintains " + String.join(", ", offending) + " - remove "
                    + (offending.size() == 1 ? "it" : "them") + " from the " + verb + ".");
        }
    }
}
