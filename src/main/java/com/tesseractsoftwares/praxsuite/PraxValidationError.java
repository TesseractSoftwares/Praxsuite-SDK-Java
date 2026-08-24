package com.tesseractsoftwares.praxsuite;

import java.util.List;

/**
 * The SDK refused the request before sending it.
 *
 * <p>Also an {@link IllegalArgumentException} in spirit, but it stays inside the PraxError
 * hierarchy so a single {@code catch (PraxError e)} covers every failure this SDK can produce.
 */
public class PraxValidationError extends PraxError {
    private static final long serialVersionUID = 1L;

    public PraxValidationError(String code, String message) {
        super(code, message, 0, List.of(), "");
    }
}
