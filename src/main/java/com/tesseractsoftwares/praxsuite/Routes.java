package com.tesseractsoftwares.praxsuite;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Builds gateway URLs.
 *
 * <p>The Praxsuite FrontDoor accepts a short form, {@code /{workspaceId}/query}, which it rewrites
 * to the backend's {@code /api/v1/gateway/{workspaceId}/query}. The SDK uses the short form: it is
 * the documented public shape, and going through the FrontDoor is what applies the edge rate limit.
 *
 * <p>Host matters. Praxsuite runs several independent tiers and a workspace exists on exactly one -
 * a workspace on another tier returns 404, not an error you can diagnose from the message.
 */
public final class Routes {

    private Routes() {}

    public static final String CLOUD_HOST = "https://gateway.praxsuite.com";

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0");

    /** Trims trailing slashes and defaults to https. */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return CLOUD_HOST;
        String url = baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        return url;
    }

    /** True for a plaintext URL that is not a loopback address. */
    public static boolean isInsecureRemote(String baseUrl) {
        if (baseUrl == null || !baseUrl.toLowerCase().startsWith("http://")) return false;
        String rest = baseUrl.substring(7);
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = hostPort.indexOf(':');
        String host = (colon >= 0 ? hostPort.substring(0, colon) : hostPort).toLowerCase();
        return !LOOPBACK.contains(host);
    }

    private static String workspaceBase(String baseUrl, String workspaceId) {
        return normalizeBaseUrl(baseUrl) + "/" + workspaceId;
    }

    public static String query(String baseUrl, String workspaceId) {
        return workspaceBase(baseUrl, workspaceId) + "/query";
    }

    public static String schema(String baseUrl, String workspaceId) {
        return workspaceBase(baseUrl, workspaceId) + "/schema";
    }

    public static String auth(String baseUrl, String workspaceId, String action) {
        return workspaceBase(baseUrl, workspaceId) + "/auth/" + action;
    }

    /**
     * An endpoint invocation URL.
     *
     * <p>The segment is escaped rather than trusted: it comes from the caller and lands in a path,
     * so a slash in it must not be able to walk out of the segment.
     */
    public static String endpoint(String baseUrl, String workspaceId, String endpointId) {
        String escaped = URLEncoder.encode(endpointId, StandardCharsets.UTF_8).replace("+", "%20");
        return workspaceBase(baseUrl, workspaceId) + "/endpoint/" + escaped;
    }

    public static String files(String baseUrl, String workspaceId, String suffix) {
        String url = workspaceBase(baseUrl, workspaceId) + "/files";
        return (suffix == null || suffix.isEmpty()) ? url : url + "/" + suffix;
    }
}
