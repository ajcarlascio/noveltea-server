package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code taxonomy.kind}. */
public enum TaxonomyKind {
    LABEL("label"),
    STATUS("status");

    private final String wire;

    TaxonomyKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<TaxonomyKind> fromWire(String value) {
        return WireEnums.lookup(TaxonomyKind.class, TaxonomyKind::wire, value);
    }
}
