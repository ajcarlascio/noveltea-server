package com.noveltea.sync.entity;

import java.util.Optional;
import java.util.function.Function;

/**
 * One writable column of a synced entity.
 *
 * @param column the database column. Comes from a fixed registry, never from a request,
 *     which is why it is safe to interpolate into SQL.
 * @param kind how the JSON value is converted and checked before it reaches the database
 * @param requiredOnCreate whether a create is rejected when this field is absent
 * @param enumLookup for {@link ColumnKind#ENUM}, the parser that rejects unknown values
 *     before Postgres would reject them as a CHECK violation
 */
public record ColumnSpec(
        String column,
        ColumnKind kind,
        boolean requiredOnCreate,
        Function<String, Optional<?>> enumLookup) {

    public static ColumnSpec of(String column, ColumnKind kind) {
        return new ColumnSpec(column, kind, false, null);
    }

    public static ColumnSpec required(String column, ColumnKind kind) {
        return new ColumnSpec(column, kind, true, null);
    }

    public static ColumnSpec requiredEnum(String column, Function<String, Optional<?>> lookup) {
        return new ColumnSpec(column, ColumnKind.ENUM, true, lookup);
    }

    public static ColumnSpec optionalEnum(String column, Function<String, Optional<?>> lookup) {
        return new ColumnSpec(column, ColumnKind.ENUM, false, lookup);
    }
}
