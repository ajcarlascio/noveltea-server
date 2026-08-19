package com.noveltea.order;

/**
 * Lexicographic ordering keys for {@code binder_item.order_key}.
 *
 * <p>A port of the standard fractional-indexing midpoint algorithm. Between any two
 * distinct keys there is always another key, so inserting or reordering never
 * requires rewriting siblings — and unlike float-based indexing, it never exhausts
 * precision (see amendment A4).
 *
 * <p>Arithmetic on these strings is always a bug: they are compared, never computed.
 *
 * <p>Clients own general reordering. The server generates a key in exactly one
 * situation — placing a conflict copy immediately after the item it forked from —
 * but uses the same algorithm so the two never disagree about ordering.
 */
public final class FractionalIndex {

    /** Base-62, in ASCII order so that string comparison equals digit comparison. */
    private static final String DIGITS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private FractionalIndex() {}

    /**
     * Returns a key sorting strictly between {@code after} and {@code before}.
     *
     * @param after  the preceding key, or null/empty for "before everything"
     * @param before the following key, or null for "after everything"
     * @throws IllegalArgumentException if {@code after >= before}, or if either key
     *         ends in {@code '0'} (which would break the algorithm's invariant that
     *         a key can always be extended downward)
     */
    public static String between(String after, String before) {
        String a = after == null ? "" : after;

        if (before != null && a.compareTo(before) >= 0) {
            throw new IllegalArgumentException(
                    "after (" + a + ") must sort before before (" + before + ")");
        }
        if (a.endsWith("0") || (before != null && before.endsWith("0"))) {
            throw new IllegalArgumentException("order keys must not end in '0'");
        }

        if (before != null) {
            int common = 0;
            while (common < before.length() && digitAt(a, common) == before.charAt(common)) {
                common++;
            }
            if (common > 0) {
                return before.substring(0, common)
                        + between(suffix(a, common), before.substring(common));
            }
        }

        int digitA = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int digitB = (before != null && !before.isEmpty())
                ? DIGITS.indexOf(before.charAt(0))
                : DIGITS.length();

        if (digitB - digitA > 1) {
            int mid = (int) Math.round(0.5 * (digitA + digitB));
            return String.valueOf(DIGITS.charAt(mid));
        }
        // Adjacent digits: descend into the next position.
        if (before != null && before.length() > 1) {
            return before.substring(0, 1);
        }
        return DIGITS.charAt(digitA) + between(suffix(a, 1), null);
    }

    /** First key in an empty list. */
    public static String first() {
        return between(null, null);
    }

    private static char digitAt(String s, int index) {
        return index < s.length() ? s.charAt(index) : '0';
    }

    private static String suffix(String s, int from) {
        return from < s.length() ? s.substring(from) : "";
    }
}
