package com.noveltea.model;

import java.util.Optional;

public enum ChangeOp {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete");

    private final String wire;

    ChangeOp(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<ChangeOp> fromWire(String value) {
        return WireEnums.lookup(ChangeOp.class, ChangeOp::wire, value);
    }
}
