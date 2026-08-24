package com.tesseractsoftwares.praxsuite;

import java.util.List;

/** See {@link PraxError} for what this means and when to catch it. */
public class PraxRateLimitError extends PraxError {
    private static final long serialVersionUID = 1L;

    public PraxRateLimitError(String code, String message, int status, List<String> details, String rawBody) {
        super(code, message, status, details, rawBody);
    }
}
