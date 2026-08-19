package com.noveltea.sync.entity;

/** How a JSON field is validated and bound. */
public enum ColumnKind {
    TEXT,
    /** Free text with no length ceiling — synopses and notes. */
    LONG_TEXT,
    UUID,
    BOOLEAN,
    INTEGER,
    TIMESTAMP,
    /** Must be a JSON object; an array or scalar would break every reader. */
    JSON_OBJECT,
    /** Any JSON value, used where the shape is genuinely open. */
    JSON_ANY,
    /** JSON array of uuid strings, stored as a Postgres uuid[]. */
    UUID_ARRAY,
    /** One of a fixed set, checked here so Postgres never has to reject it. */
    ENUM
}
