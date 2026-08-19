package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code compile_job.status}. */
public enum CompileJobStatus {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed");

    private final String wire;

    CompileJobStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }

    public static Optional<CompileJobStatus> fromWire(String value) {
        return WireEnums.lookup(CompileJobStatus.class, CompileJobStatus::wire, value);
    }
}
