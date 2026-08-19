package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code project_member.role}. */
public enum MemberRole {
    OWNER("owner"),
    EDITOR("editor"),
    COMMENTER("commenter"),
    VIEWER("viewer");

    private final String wire;

    MemberRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<MemberRole> fromWire(String value) {
        return WireEnums.lookup(MemberRole.class, MemberRole::wire, value);
    }
}
