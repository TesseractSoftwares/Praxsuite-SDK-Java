package com.tesseractsoftwares.praxsuite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the tables and columns this credential is allowed to see.
 *
 * <p>What comes back is filtered by scope. A table you have not granted access to simply is not
 * listed, and a column hidden from the credential is absent rather than empty - so this is also the
 * quickest way to tell a typo apart from a missing scope, which look identical in a 403.
 *
 * <p>Cached for the life of the client: the schema only changes when you change it.
 */
public final class PraxSchema {

    private final Praxsuite client;
    private volatile Map<String, Map<String, Object>> cache;

    PraxSchema(Praxsuite client) {
        this.client = client;
    }

    /** Every visible table, keyed by name. Cached after the first call. */
    public Map<String, Map<String, Object>> tables() { return tables(false); }

    public Map<String, Map<String, Object>> tables(boolean forceReload) {
        Map<String, Map<String, Object>> current = cache;
        if (current != null && !forceReload) return current;

        String url = Routes.schema(client.baseUrl(), client.workspaceId());
        Map<String, Object> body = Responses.unwrapEnvelope(
            client.send("GET", url, null, true, null));

        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        if (body.get("tables") instanceof List<?> listed) {
            for (Object entry : listed) {
                if (entry instanceof Map<?, ?> raw) {
                    Map<String, Object> table = new LinkedHashMap<>();
                    raw.forEach((k, v) -> table.put(String.valueOf(k), v));
                    loaded.put(String.valueOf(table.getOrDefault("name", "")), table);
                }
            }
        }
        cache = loaded;
        return loaded;
    }

    /** One table's definition, or null when it is not visible to this credential. */
    public Map<String, Object> table(String name) {
        return tables().get(name);
    }

    /** The column names visible on a table. */
    public List<String> columns(String table) {
        Map<String, Object> definition = table(table);
        List<String> names = new ArrayList<>();
        if (definition != null && definition.get("columns") instanceof List<?> listed) {
            for (Object c : listed) {
                if (c instanceof Map<?, ?> col) names.add(String.valueOf(col.get("name")));
            }
        }
        return names;
    }

    public boolean hasTable(String name) {
        return table(name) != null;
    }
}
