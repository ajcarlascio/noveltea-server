package com.noveltea.sync;

/** Why a pushed change was not applied as sent. */
public final class ConflictReason {
    private ConflictReason() {}

    /** base_version did not match; a conflict copy holds the client's text. */
    public static final String VERSION_MISMATCH = "version_mismatch";
    /** A create arrived for an id that already exists with different content. */
    public static final String DUPLICATE_CREATE = "duplicate_create";
    /** An update arrived for an entity the server no longer has. */
    public static final String ENTITY_MISSING = "entity_missing";
    /** The change was malformed — missing or unknown entity type, id, or op. */
    public static final String INVALID_REQUEST = "invalid_request";
    /** Entity type not yet handled by the push path. */
    public static final String NOT_IMPLEMENTED = "not_implemented";
}
