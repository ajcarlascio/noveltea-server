package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code binder_item.type}. */
public enum BinderItemType {
    FOLDER("folder"),
    DOCUMENT("document"),
    TRASH("trash");

    private final String wire;

    BinderItemType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<BinderItemType> fromWire(String value) {
        return WireEnums.lookup(BinderItemType.class, BinderItemType::wire, value);
    }
}
