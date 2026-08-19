package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code compile_job.destination}. */
public enum CompileDestination {
    /** Staged briefly on the server, streamed to the client once, then purged. */
    DOWNLOAD("download"),
    /** Written to the operator's configured mount point and kept. */
    SERVER("server"),
    /** Commercial destination. A Core build refuses it. */
    CLOUD("cloud");

    private final String wire;

    CompileDestination(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<CompileDestination> fromWire(String value) {
        return WireEnums.lookup(CompileDestination.class, CompileDestination::wire, value);
    }
}
