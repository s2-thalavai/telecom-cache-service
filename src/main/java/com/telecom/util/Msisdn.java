package com.telecom.util;

import com.telecom.exception.InvalidMsisdnException;

import java.util.regex.Pattern;

/**
 * Normalises MSISDNs BEFORE they become cache keys:
 * "+91 98765-43210", "919876543210", "09876543210" -> "9876543210".
 * Prevents duplicate cache entries, low hit ratios and key-injection/poisoning.
 */
public final class Msisdn {

    private static final Pattern VALID = Pattern.compile("[6-9]\\d{9}");

    private Msisdn() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidMsisdnException("MSISDN is required");
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!VALID.matcher(digits).matches()) {
            throw new InvalidMsisdnException("Invalid MSISDN");
        }
        return digits;
    }

    /** For logs: 98XXXXX210 - never log full subscriber numbers. */
    public static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 10) {
            return "XXXXXXXXXX";
        }
        return msisdn.substring(0, 2) + "XXXXX" + msisdn.substring(7);
    }
}
