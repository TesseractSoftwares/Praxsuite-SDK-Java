package com.tesseractsoftwares.praxsuite;

import java.util.List;

/**
 * Every failure the gateway reports. Subclasses exist for the cases worth catching separately.
 *
 * <p>{@link #code()} is stable and safe to branch on. {@link #getMessage()} is human-facing and may
 * change between releases.
 */
public class PraxError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int status;
    private final List<String> details;
    private final String rawBody;

    public PraxError(String code, String message) { this(code, message, 0, List.of(), ""); }

    public PraxError(String code, String message, int status) {
        this(code, message, status, List.of(), "");
    }

    public PraxError(String code, String message, int status, List<String> details, String rawBody) {
        super(message == null || message.isEmpty() ? code : message);
        this.code = code == null ? "UNKNOWN" : code;
        this.status = status;
        this.details = details == null ? List.of() : List.copyOf(details);
        this.rawBody = rawBody == null ? "" : rawBody;
    }

    /** Stable machine-readable code, e.g. RATE_LIMIT_EXCEEDED. */
    public String code() { return code; }

    /** HTTP status, or 0 for a transport failure that never reached the gateway. */
    public int status() { return status; }

    /** Per-field validation details, when the gateway supplied them. */
    public List<String> details() { return details; }

    /** Raw response body, for diagnostics. Never contains your API key. */
    public String rawBody() { return rawBody; }

    /** The credential is missing, expired, or does not belong to this workspace. */
    public boolean isAuthFailure() { return status == 401; }

    /** Authenticated, but this credential or role is not scoped for the operation. */
    public boolean isForbidden() { return status == 403; }

    /** Too many calls. Backing off and retrying will succeed. */
    public boolean isRateLimited() { return "RATE_LIMIT_EXCEEDED".equals(code); }

    /**
     * A plan allowance is exhausted. Retrying will NOT help.
     *
     * <p>Shares HTTP 429 with a rate limit, which is exactly why this is a separate check.
     */
    public boolean isQuotaExceeded() {
        return "QUOTA_EXCEEDED".equals(code) || "EGRESS_LIMIT_EXCEEDED".equals(code);
    }

    /** Transport failure: offline, DNS, TLS, or timeout. */
    public boolean isNetworkError() {
        return "NETWORK_ERROR".equals(code) || "TIMEOUT".equals(code);
    }

    /** Worth retrying automatically. Quota exhaustion deliberately is not. */
    public boolean isTransient() {
        if (isQuotaExceeded()) return false;
        return isNetworkError() || isRateLimited() || (status >= 500 && status <= 599);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("[Praxsuite] ").append(code);
        if (status > 0) s.append(" (HTTP ").append(status).append(')');
        s.append(": ").append(getMessage());
        for (String d : details) s.append("\n  - ").append(d);
        return s.toString();
    }

    /**
     * Builds the most specific subclass for a code and status.
     *
     * <p>Classification lives in one place so the transport, the parser and the tests cannot
     * disagree about what a 429 means.
     */
    public static PraxError of(String code, String message, int status,
                               List<String> details, String rawBody) {
        if ("QUOTA_EXCEEDED".equals(code) || "EGRESS_LIMIT_EXCEEDED".equals(code)) {
            return new PraxQuotaExceededError(code, message, status, details, rawBody);
        }
        if ("RATE_LIMIT_EXCEEDED".equals(code)) {
            return new PraxRateLimitError(code, message, status, details, rawBody);
        }
        if ("TIMEOUT".equals(code)) {
            return new PraxTimeoutError(code, message, status, details, rawBody);
        }
        if ("NETWORK_ERROR".equals(code)) {
            return new PraxNetworkError(code, message, status, details, rawBody);
        }
        if (status == 401) return new PraxAuthError(code, message, status, details, rawBody);
        if (status == 403) return new PraxForbiddenError(code, message, status, details, rawBody);
        return new PraxError(code, message, status, details, rawBody);
    }
}
