package com.noveltea.auth;

import java.security.SecureRandom;

/**
 * The one place that decides what counts as a password.
 *
 * <p>The rule was written out three times before this existed — registration, password
 * reset, and then the two administration paths — which is three chances for them to
 * disagree about the minimum and one of them to end up laxer than the others. A caller
 * that only wants to <em>know</em> uses {@link #isStrongEnough}; a caller that wants to
 * refuse uses {@link #require}.
 *
 * <p>Length is the whole policy on purpose. Composition rules ("one digit, one symbol")
 * push people towards {@code Password1!} and are worth less than four more characters.
 */
public final class Passwords {

    /** Long enough that a passphrase clears it and {@code hunter2} does not. */
    public static final int MINIMUM_LENGTH = 12;

    /** Excludes I, L, O, 0, 1: a generated password gets read off a screen and retyped. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static boolean isStrongEnough(String password) {
        return password != null && password.length() >= MINIMUM_LENGTH;
    }

    public static void require(String password) {
        if (!isStrongEnough(password)) {
            throw new IllegalArgumentException(
                    "password must be at least " + MINIMUM_LENGTH + " characters");
        }
    }

    /**
     * A password for an account whose holder is not present to choose one.
     *
     * <p>Grouped with hyphens because it is going to be copied out of an admin screen and
     * into a chat message by hand. Always accompanied by {@code must_change_password}, so
     * its lifetime is one sign-in.
     */
    public static String generate() {
        StringBuilder password = new StringBuilder(20);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                password.append('-');
            }
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
