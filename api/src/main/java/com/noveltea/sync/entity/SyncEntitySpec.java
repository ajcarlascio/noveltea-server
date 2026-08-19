package com.noveltea.sync.entity;

import static com.noveltea.sync.entity.ColumnKind.*;
import static com.noveltea.sync.entity.ColumnSpec.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.model.EntityType;
import com.noveltea.model.ExportFormat;
import com.noveltea.model.MetadataFieldType;
import com.noveltea.model.TaxonomyKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What each synced entity looks like on the wire and in the database.
 *
 * <p>Declaring this once means a new entity type is a table entry rather than another
 * near-identical writer, and — more importantly — that every CHECK constraint in the
 * schema has a matching check here. A constraint that only exists in Postgres surfaces as
 * a 500 and fails an entire push; caught here it is one reported conflict.
 *
 * @param parentRefs columns pointing at rows that must belong to the same project. These
 *     are an authorization boundary, not just referential integrity: without them a
 *     caller could attach a row to another account's collection.
 * @param invariants cross-field rules that mirror multi-column CHECK constraints
 */
public record SyncEntitySpec(
        EntityType type,
        String table,
        boolean hasProjectId,
        List<ColumnSpec> columns,
        List<ParentRef> parentRefs,
        List<Invariant> invariants) {

    /** @param table the table the referenced id must exist in, scoped to the project */
    public record ParentRef(String column, String table, boolean requiredOnCreate) {}

    /**
     * A cross-field rule that mirrors a multi-column CHECK constraint.
     *
     * @param mirrorsConstraint the exact constraint name in Postgres. This is not
     *     documentation: a test reads {@code pg_constraint} and fails when a CHECK exists
     *     with no invariant mirroring it, which is what stops the two drifting apart.
     * @param check returns an error message when violated, or empty when satisfied
     */
    public record Invariant(String mirrorsConstraint, Function<JsonNode, Optional<String>> check) {}

    private static final List<SyncEntitySpec> ALL = List.of(
            new SyncEntitySpec(
                    EntityType.TAXONOMY, "taxonomy", true,
                    List.of(
                            requiredEnum("kind", v -> TaxonomyKind.fromWire(v).map(x -> (Object) x.wire())),
                            required("name", TEXT),
                            of("color", TEXT),
                            required("order_key", TEXT),
                            of("deleted_at", TIMESTAMP)),
                    List.of(),
                    List.of(new Invariant("taxonomy_color_label_only", data -> {
                        // Mirrors CHECK taxonomy_color_label_only.
                        boolean hasColour = has(data, "color");
                        boolean isLabel = "label".equalsIgnoreCase(text(data, "kind"));
                        return hasColour && !isLabel && data.hasNonNull("kind")
                                ? Optional.of("color may only be set on a label")
                                : Optional.empty();
                    }))),

            new SyncEntitySpec(
                    EntityType.CUSTOM_METADATA_FIELD, "custom_metadata_field", true,
                    List.of(
                            required("name", TEXT),
                            requiredEnum("field_type",
                                    v -> MetadataFieldType.fromWire(v).map(x -> (Object) x.wire())),
                            of("options", JSON_ANY),
                            required("order_key", TEXT),
                            of("deleted_at", TIMESTAMP)),
                    List.of(),
                    List.of(new Invariant("custom_metadata_field_options_for_select", data -> {
                        // Mirrors CHECK custom_metadata_field_options_for_select.
                        boolean hasOptions = has(data, "options");
                        boolean isSelect = "select".equalsIgnoreCase(text(data, "field_type"));
                        return hasOptions && !isSelect && data.hasNonNull("field_type")
                                ? Optional.of("options may only be set on a select field")
                                : Optional.empty();
                    }))),

            new SyncEntitySpec(
                    EntityType.CUSTOM_METADATA_VALUE, "custom_metadata_value", false,
                    List.of(of("value", JSON_ANY)),
                    List.of(
                            new ParentRef("binder_item_id", "binder_item", true),
                            new ParentRef("field_id", "custom_metadata_field", true)),
                    List.of()),

            new SyncEntitySpec(
                    EntityType.COLLECTION, "collection", true,
                    List.of(
                            required("name", TEXT),
                            of("query", JSON_OBJECT),
                            of("is_smart", BOOLEAN),
                            of("color", TEXT),
                            required("order_key", TEXT),
                            of("deleted_at", TIMESTAMP)),
                    List.of(),
                    List.of(new Invariant("collection_smart_has_query", data ->
                            // Mirrors CHECK collection_smart_has_query.
                            bool(data, "is_smart") && !has(data, "query")
                                    ? Optional.of("a smart collection requires a query")
                                    : Optional.empty()))),

            new SyncEntitySpec(
                    EntityType.COLLECTION_ITEM, "collection_item", false,
                    List.of(required("order_key", TEXT)),
                    List.of(
                            new ParentRef("collection_id", "collection", true),
                            new ParentRef("binder_item_id", "binder_item", true)),
                    List.of()),

            new SyncEntitySpec(
                    EntityType.COMPILE_PRESET, "compile_preset", true,
                    List.of(
                            required("name", TEXT),
                            requiredEnum("format", v -> ExportFormat.fromWire(v).map(x -> (Object) x.wire())),
                            of("included_binder_items", UUID_ARRAY),
                            of("include_query", JSON_OBJECT),
                            of("separator_rules", JSON_OBJECT),
                            of("title_page", JSON_OBJECT),
                            of("front_matter", JSON_OBJECT),
                            of("deleted_at", TIMESTAMP)),
                    List.of(),
                    List.of(new Invariant("compile_preset_has_selection", data ->
                            // Mirrors CHECK compile_preset_has_selection.
                            !has(data, "included_binder_items") && !has(data, "include_query")
                                    ? Optional.of("a preset needs included_binder_items or include_query")
                                    : Optional.empty()))));

    private static final Map<EntityType, SyncEntitySpec> BY_TYPE =
            ALL.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(SyncEntitySpec::type, s -> s));

    public static Optional<SyncEntitySpec> forType(EntityType type) {
        return Optional.ofNullable(BY_TYPE.get(type));
    }

    public static List<SyncEntitySpec> all() {
        return ALL;
    }

    public Optional<ColumnSpec> column(String name) {
        return columns.stream().filter(c -> c.column().equals(name)).findFirst();
    }

    private static boolean has(JsonNode data, String field) {
        return data != null && data.hasNonNull(field);
    }

    private static String text(JsonNode data, String field) {
        return has(data, field) ? data.get(field).asText() : null;
    }

    private static boolean bool(JsonNode data, String field) {
        return has(data, field) && data.get(field).asBoolean();
    }
}
