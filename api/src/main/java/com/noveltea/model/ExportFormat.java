package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code compile_preset.format}. */
public enum ExportFormat {
    MD("md"),
    HTML("html"),
    TXT("txt"),
    RTF("rtf"),
    DOCX("docx"),
    ODT("odt"),
    EPUB("epub"),
    PDF("pdf");

    private final String wire;

    ExportFormat(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<ExportFormat> fromWire(String value) {
        return WireEnums.lookup(ExportFormat.class, ExportFormat::wire, value);
    }
}
