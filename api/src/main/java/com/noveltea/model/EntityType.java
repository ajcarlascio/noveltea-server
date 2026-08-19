package com.noveltea.model;

import java.util.Optional;

/** Entity kinds that appear in {@code change_log} and can be synced. */
public enum EntityType {
    BINDER_ITEM("binder_item", "binder_item"),
    DOCUMENT("document", "document"),
    TAXONOMY("taxonomy", "taxonomy"),
    CUSTOM_METADATA_FIELD("custom_metadata_field", "custom_metadata_field"),
    CUSTOM_METADATA_VALUE("custom_metadata_value", "custom_metadata_value"),
    COLLECTION("collection", "collection"),
    COLLECTION_ITEM("collection_item", "collection_item"),
    COMPILE_PRESET("compile_preset", "compile_preset"),
    /**
     * Sharing is a commercial feature, but Core must still be able to READ these rows
     * from the change feed — a client syncing a shared project receives them. Core has no
     * writer for it, so a push reports not_implemented rather than crashing on an
     * unrecognised type.
     */
    PROJECT_MEMBER("project_member", "project_member"),
    /**
     * Only MANUAL snapshots ever appear in the feed. Automatic captures stay on the device
     * that made them — see migration 20260819-19 for why.
     */
    SNAPSHOT("snapshot", "snapshot");

    private final String wire;
    private final String table;

    EntityType(String wire, String table) {
        this.wire = wire;
        this.table = table;
    }

    public String wire() {
        return wire;
    }

    /** Safe to interpolate: it comes from this enum, never from a request. */
    public String table() {
        return table;
    }

    public static Optional<EntityType> fromWire(String value) {
        return WireEnums.lookup(EntityType.class, EntityType::wire, value);
    }
}
