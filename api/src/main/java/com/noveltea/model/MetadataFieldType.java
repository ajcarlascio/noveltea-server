package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code custom_metadata_field.field_type}. */
public enum MetadataFieldType {
    TEXT("text"),
    NUMBER("number"),
    DATE("date"),
    BOOLEAN("boolean"),
    SELECT("select");

    private final String wire;

    MetadataFieldType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<MetadataFieldType> fromWire(String value) {
        return WireEnums.lookup(MetadataFieldType.class, MetadataFieldType::wire, value);
    }
}
