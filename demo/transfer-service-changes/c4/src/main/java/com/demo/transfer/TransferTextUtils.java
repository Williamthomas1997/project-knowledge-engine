package com.demo.transfer;

import lombok.extern.slf4j.Slf4j;

/** A text utility both transfer APIs use. Sharing it never makes two APIs related (shared-logic exclusion). */
@Slf4j
public final class TransferTextUtils {

    private TransferTextUtils() {
    }

    public static String normalise(String value) {
        log.debug("Normalising an identifier");
        return value == null ? null : value.strip().toUpperCase();
    }
}
