package com.tesseractsoftwares.praxsuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A chained query. Build it, then call a terminal method.
 *
 * <pre>{@code
 * Page page = prax.data().table("Scores")
 *     .select("Player", "Score")
 *     .where(Filters.gte("Score", 100))
 *     .orderByDescending("Score")
 *     .limit(20)
 *     .fetch();
 * }</pre>
 *
 * <p>Nothing is sent until a terminal method - {@code fetch}, {@code first}, {@code count},
 * {@code exists}, {@code all} - so building a query costs nothing.
 */
public final class Query {

    /**
     * The root table's alias inside the request. The gateway addresses tables through {@code refs},
     * so the alias is an implementation detail callers never see.
     */
    static final String ROOT = "t";

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    /** Enforced by the gateway to stop injection through the alias. */
    private static final Pattern ALIAS = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private final PraxData data;
    private final String table;
    private final List<Object> select = new ArrayList<>();
    private final List<Map<String, Object>> where = new ArrayList<>();
    private final List<Map<String, Object>> order = new ArrayList<>();
    private final List<String> groupBy = new ArrayList<>();
    private final List<Map<String, Object>> having = new ArrayList<>();
    private final Map<String, String> extraRefs = new LinkedHashMap<>();
    private Integer limit;
    private Integer offset;
    private boolean includeTotal;

    Query(PraxData data, String table) {
        if (table == null || table.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "A table name or id is required.");
        }
        this.data = data;
        this.table = table.trim();
    }

    // ── building ────────────────────────────────────────────────────────────

    /**
     * Restricts the columns returned.
     *
     * <p>Worth doing on wide tables: the gateway meters egress against the workspace's plan, so
     * fetching columns you discard costs real allowance.
     */
    public Query select(String... columns) {
        if (columns != null) {
            for (String c : columns) if (c != null && !c.isBlank()) select.add(c.trim());
        }
        return this;
    }

    /** Includes rows from a related table as a nested list on each row. */
    public Query include(String relatedTable, List<String> columns, Integer rowLimit) {
        if (relatedTable == null || relatedTable.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "A related table name or id is required.");
        }
        String alias = "r" + (extraRefs.size() + 1);
        extraRefs.put(alias, relatedTable.trim());

        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("table", alias);
        if (columns != null && !columns.isEmpty()) {
            List<String> picked = new ArrayList<>();
            for (String c : columns) if (c != null && !c.isBlank()) picked.add(c.trim());
            if (!picked.isEmpty()) relation.put("select", picked);
        }
        if (rowLimit != null) relation.put("limit", rowLimit);
        select.add(relation);
        return this;
    }

    /** Adds conditions, built with {@link Filters}. Repeated calls are ANDed. */
    @SafeVarargs
    @SuppressWarnings("varargs")  // the array is only read
    public final Query where(Map<String, Object>... conditions) {
        if (conditions != null) {
            for (Map<String, Object> c : conditions) if (c != null && !c.isEmpty()) where.add(c);
        }
        return this;
    }

    /** Shorthand for a single equality condition. */
    public Query whereEquals(String column, Object value) {
        where.add(Filters.eq(column, value));
        return this;
    }

    public Query orderBy(String column) { return orderBy(column, true); }

    public Query orderByDescending(String column) { return orderBy(column, false); }

    public Query orderBy(String column, boolean ascending) {
        if (column == null || column.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "A column name is required.");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("field", column.trim());
        o.put("dir", ascending ? "asc" : "desc");
        order.add(o);
        return this;
    }

    public Query limit(int n) {
        // The gateway clamps limit up to a minimum of 1, so 0 never means "no rows".
        this.limit = Math.max(1, n);
        return this;
    }

    public Query offset(int n) {
        this.offset = Math.max(0, n);
        return this;
    }

    /**
     * Asks for the total match count alongside the page. Off by default: it costs the server a
     * second counting pass.
     */
    public Query withTotalCount() {
        this.includeTotal = true;
        return this;
    }

    public Query groupBy(String... columns) {
        if (columns != null) {
            for (String c : columns) if (c != null && !c.isBlank()) groupBy.add(c.trim());
        }
        return this;
    }

    /** Conditions applied after grouping. Built the same way as {@link #where}. */
    @SafeVarargs
    @SuppressWarnings("varargs")  // the array is only read
    public final Query having(Map<String, Object>... conditions) {
        if (conditions != null) {
            for (Map<String, Object> c : conditions) if (c != null && !c.isEmpty()) having.add(c);
        }
        return this;
    }

    /**
     * Adds an aggregate column, e.g. {@code aggregate("sum", "Score", "total_score")}.
     *
     * <p>Aggregations are disabled on a table scope by default, so a 403 here is a workspace setting
     * to change, not a mistake in the query.
     */
    public Query aggregate(String fn, String column, String alias) {
        String normalized = fn == null ? "" : fn.trim().toLowerCase();
        if (!AGGREGATES.contains(normalized)) {
            throw new PraxValidationError("INVALID_REQUEST",
                "Unsupported aggregate \"" + fn + "\". The gateway accepts "
                    + String.join(", ", new java.util.TreeSet<>(AGGREGATES)) + ".");
        }
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "Invalid aggregate alias \"" + alias + "\". Use letters, digits and underscore, "
                    + "starting with a letter.");
        }
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("field", (column == null || column.isBlank()) ? "*" : column.trim());
        agg.put("fn", normalized);
        agg.put("alias", alias.trim());
        select.add(agg);
        return this;
    }

    // ── terminal ────────────────────────────────────────────────────────────

    /** Runs the query and returns one page. */
    public Responses.Page fetch() {
        return Responses.parsePage(data.execute(build()));
    }

    /**
     * The first matching row, or null when nothing matched.
     *
     * <p>An empty result is not an error - most callers want to branch on it.
     */
    public Map<String, Object> first() {
        Integer saved = limit;
        limit = 1;
        try {
            return fetch().first();
        } finally {
            limit = saved;
        }
    }

    public boolean exists() { return first() != null; }

    /**
     * The number of matching rows, ignoring limit and offset.
     *
     * <p>Implemented as {@code includeTotalCount} plus a one-row fetch: the gateway clamps limit up
     * to a minimum of 1, so a zero-row request is not possible and asking for one silently returns
     * a row.
     */
    public long count() {
        Integer savedLimit = limit;
        Integer savedOffset = offset;
        boolean savedTotal = includeTotal;
        limit = 1;
        offset = null;
        includeTotal = true;
        try {
            Responses.Page page = fetch();
            if (page.total() == null) {
                throw new PraxError("TOTAL_COUNT_UNAVAILABLE",
                    "The gateway returned no total count. Aggregations are probably disabled on "
                        + "this table's scope - enable them in the workspace's API Gateway "
                        + "settings, or use aggregate(\"count\", \"*\", \"n\").");
            }
            return page.total();
        } finally {
            limit = savedLimit;
            offset = savedOffset;
            includeTotal = savedTotal;
        }
    }

    /** Pages through every matching row. */
    public List<Map<String, Object>> all() { return all(200, null); }

    /**
     * Pages through every matching row.
     *
     * <p>Each page is a separate request, and the gateway may clamp {@code pageSize} below what you
     * asked for - {@code meta.limit} is read back rather than assumed, because a clamp would
     * otherwise turn this into an infinite loop re-reading the same rows.
     */
    public List<Map<String, Object>> all(int pageSize, Integer maxRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Integer savedLimit = limit;
        Integer savedOffset = offset;
        try {
            int at = offset == null ? 0 : offset;
            while (true) {
                limit = Math.max(1, pageSize);
                offset = at;
                Responses.Page page = fetch();
                rows.addAll(page.rows());

                if (maxRows != null && rows.size() >= maxRows) {
                    return new ArrayList<>(rows.subList(0, maxRows));
                }
                int step = page.limit() > 0 ? page.limit() : page.rows().size();
                if (page.rows().isEmpty() || page.rows().size() < step) return rows;
                at += page.rows().size();
            }
        } finally {
            limit = savedLimit;
            offset = savedOffset;
        }
    }

    /**
     * The request body this query will send.
     *
     * <p>Public because seeing it is the fastest way to understand a 400, and because the tests
     * assert on it.
     */
    public Map<String, Object> build() {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put(ROOT, data.resolveTable(table));
        extraRefs.forEach((alias, name) -> refs.put(alias, data.resolveTable(name)));

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("from", ROOT);
        if (!select.isEmpty()) query.put("select", new ArrayList<>(select));
        if (!where.isEmpty()) query.put("where", new ArrayList<>(where));
        if (!order.isEmpty()) query.put("orderBy", new ArrayList<>(order));
        if (!groupBy.isEmpty()) query.put("groupBy", new ArrayList<>(groupBy));
        if (!having.isEmpty()) query.put("having", new ArrayList<>(having));
        if (limit != null) query.put("limit", limit);
        if (offset != null) query.put("offset", offset);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("refs", refs);
        request.put("query", query);
        // includeTotalCount sits BESIDE query, not inside it. Nesting it is silently ignored and
        // the total then comes back absent forever.
        if (includeTotal) request.put("includeTotalCount", Boolean.TRUE);
        return request;
    }

    /** Convenience for {@code in} over a collection, so callers need not import Filters. */
    public Query whereIn(String column, Collection<?> values) {
        where.add(Filters.in(column, values));
        return this;
    }

    /** Convenience for {@code between}. */
    public Query whereBetween(String column, Object low, Object high) {
        where.add(Filters.between(column, low, high));
        return this;
    }

    /** Convenience for a list of conditions built at runtime. */
    public Query where(List<Map<String, Object>> conditions) {
        if (conditions != null) {
            for (Map<String, Object> c : conditions) if (c != null && !c.isEmpty()) where.add(c);
        }
        return this;
    }

    /** Kept so {@link Arrays} stays used if a future edit drops the varargs paths. */
    static List<String> asList(String... items) {
        return items == null ? List.of() : Arrays.asList(items);
    }
}
