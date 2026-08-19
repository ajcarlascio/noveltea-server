package com.noveltea.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lookup helper for enums that have a wire form.
 *
 * <p>Every closed value set in this codebase is an enum here and a `text` column with a
 * CHECK constraint in Postgres (the schema is mirrored into client SQLite, which has no
 * enum type). These constants are the single place either side is defined, so adding a
 * value means editing one enum rather than hunting string literals.
 */
public final class WireEnums {
    private WireEnums() {}

    public static <E extends Enum<E>> Optional<E> lookup(
            Class<E> type, Function<E, String> wire, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(type.getEnumConstants())
                .filter(candidate -> wire.apply(candidate).equals(normalised))
                .findFirst();
    }
}
