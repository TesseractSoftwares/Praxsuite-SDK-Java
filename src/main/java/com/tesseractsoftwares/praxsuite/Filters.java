package com.tesseractsoftwares.praxsuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where conditions.
 *
 * <p>Only the operators the gateway's PraxQL parser accepts are exposed:
 * {@code eq neq gt gte lt lte like ilike in is between contains textsearch}.
 *
 * <p>{@link #startsWith} and {@link #endsWith} exist as conveniences but compile down to
 * {@code like} with the wildcard already applied, and {@link #isNull}/{@link #isNotNull} compile to
 * {@code is}/{@code neq} against null. Nothing here can produce an operator the server would
 * reject: offering one only moves the failure to runtime, on someone else's machine.
 */
public final class Filters {

    private Filters() {}

    /** The complete set the gateway implements. Anything else is rejected at parse time. */
    public static final Set<String> SUPPORTED_OPERATORS = Set.of(
        "eq", "neq", "gt", "gte", "lt", "lte", "like", "ilike", "in", "is", "between",
        "contains", "textsearch");

    private static Map<String, Object> simple(String field, String op, Object value) {
        if (field == null || field.isBlank()) {
            throw new PraxValidationError("INVALID_REQUEST", "A column name is required.");
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", field.trim());
        c.put("op", op);
        c.put("value", value);
        return c;
    }

    public static Map<String, Object> eq(String field, Object value)  { return simple(field, "eq", value); }
    public static Map<String, Object> neq(String field, Object value) { return simple(field, "neq", value); }
    public static Map<String, Object> gt(String field, Object value)  { return simple(field, "gt", value); }
    public static Map<String, Object> gte(String field, Object value) { return simple(field, "gte", value); }
    public static Map<String, Object> lt(String field, Object value)  { return simple(field, "lt", value); }
    public static Map<String, Object> lte(String field, Object value) { return simple(field, "lte", value); }

    /** SQL LIKE, case-sensitive. You supply the wildcards. */
    public static Map<String, Object> like(String field, String pattern) {
        return simple(field, "like", pattern);
    }

    /** Case-insensitive LIKE. */
    public static Map<String, Object> ilike(String field, String pattern) {
        return simple(field, "ilike", pattern);
    }

    /** Substring match, no wildcards needed. */
    public static Map<String, Object> contains(String field, String text) {
        return simple(field, "contains", text);
    }

    /** Full-text search over the column. */
    public static Map<String, Object> textSearch(String field, String q) {
        return simple(field, "textsearch", q);
    }

    /** Prefix match. Compiles to {@code like 'value%'} - there is no startsWith operator. */
    public static Map<String, Object> startsWith(String field, String value) {
        return simple(field, "like", value + "%");
    }

    /** Suffix match. Compiles to {@code like '%value'}. */
    public static Map<String, Object> endsWith(String field, String value) {
        return simple(field, "like", "%" + value);
    }

    /** {@code field IS NULL}. The gateway's {@code is} operator only tests for null. */
    public static Map<String, Object> isNull(String field) {
        return simple(field, "is", null);
    }

    /** {@code field IS NOT NULL}. Compiles to {@code neq null}. */
    public static Map<String, Object> isNotNull(String field) {
        return simple(field, "neq", null);
    }

    /**
     * {@code field IN (...)}.
     *
     * <p>At least one value is required: an empty IN matches nothing, which is almost never what a
     * caller means and is silent when it happens.
     */
    public static Map<String, Object> in(String field, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST",
                "in(\"" + field + "\", []) needs at least one value. An empty IN matches nothing - "
                    + "omit the filter instead.");
        }
        return simple(field, "in", new ArrayList<>(values));
    }

    /** {@code field IN (...)}, varargs form. */
    public static Map<String, Object> in(String field, Object... values) {
        return in(field, values == null ? List.of() : Arrays.asList(values));
    }

    /** {@code field BETWEEN low AND high}, inclusive. */
    public static Map<String, Object> between(String field, Object low, Object high) {
        return simple(field, "between", Arrays.asList(low, high));
    }

    /** Matches when any child matches. */
    @SafeVarargs
    @SuppressWarnings("varargs")  // read-only use of the array; see allOf for the same reasoning
    public static Map<String, Object> anyOf(Map<String, Object>... filters) {
        return anyOf(filters == null ? List.<Map<String, Object>>of() : Arrays.asList(filters));
    }

    /** Matches when any child matches. List form, for a set built at runtime. */
    public static Map<String, Object> anyOf(List<Map<String, Object>> filters) {
        return group("or", filters);
    }

    /**
     * Matches when every child matches.
     *
     * <p>Top-level conditions are already ANDed, so this is only needed to nest an AND group inside
     * an {@link #anyOf}.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")  // the array is only read, never stored or written through
    public static Map<String, Object> allOf(Map<String, Object>... filters) {
        return allOf(filters == null ? List.<Map<String, Object>>of() : Arrays.asList(filters));
    }

    /** Matches when every child matches. List form, for a set built at runtime. */
    public static Map<String, Object> allOf(List<Map<String, Object>> filters) {
        return group("and", filters);
    }

    private static Map<String, Object> group(String key, List<Map<String, Object>> filters) {
        if (filters == null || filters.isEmpty()) {
            throw new PraxValidationError("INVALID_REQUEST", key + " needs at least one filter.");
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put(key, new ArrayList<>(filters));
        return g;
    }
}
