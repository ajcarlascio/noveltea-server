package com.noveltea.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.support.AbstractPostgresTest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves each enum is the same set of values the database will actually accept.
 *
 * <p>The enums and the CHECK constraints are two declarations of one fact, written in
 * different languages. Nothing but a test keeps them honest: add a value to the enum and
 * forget the migration and every write of it fails at runtime; add it to the migration
 * only and the server rejects data the database would have taken.
 *
 * <p>The expected values are read out of {@code pg_constraint}, not hard-coded here, so
 * this compares the enum against the live schema rather than against a third copy.
 */
class EnumSchemaAlignmentTest extends AbstractPostgresTest {

    private static final Pattern LITERAL = Pattern.compile("'([^']+)'::text");

    /** @param exactMatch false when the column deliberately allows a subset of the enum */
    record Alignment(String label, String constraint, List<String> enumWires, boolean exactMatch) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static <E extends Enum<E>> List<String> wires(Class<E> type, Function<E, String> wire) {
        return Arrays.stream(type.getEnumConstants()).map(wire).toList();
    }

    static Stream<Alignment> alignments() {
        return Stream.of(
                new Alignment("BinderItemType", "binder_item_type_valid",
                        wires(BinderItemType.class, BinderItemType::wire), true),
                new Alignment("ChangeOp", "change_log_op_valid",
                        wires(ChangeOp.class, ChangeOp::wire), true),
                new Alignment("EntityType", "change_log_entity_type_valid",
                        wires(EntityType.class, EntityType::wire), true),
                new Alignment("TaxonomyKind", "taxonomy_kind_valid",
                        wires(TaxonomyKind.class, TaxonomyKind::wire), true),
                new Alignment("MetadataFieldType", "custom_metadata_field_type_valid",
                        wires(MetadataFieldType.class, MetadataFieldType::wire), true),
                new Alignment("ExportFormat (preset)", "compile_preset_format_valid",
                        wires(ExportFormat.class, ExportFormat::wire), true),
                new Alignment("ExportFormat (job)", "compile_job_format_valid",
                        wires(ExportFormat.class, ExportFormat::wire), true),
                new Alignment("DevicePlatform", "device_platform_valid",
                        wires(DevicePlatform.class, DevicePlatform::wire), true),
                new Alignment("MemberRole", "project_member_role_valid",
                        wires(MemberRole.class, MemberRole::wire), true),
                // Invitations cannot confer ownership, so this column is a strict subset.
                new Alignment("MemberRole (invitation)", "project_invitation_role_valid",
                        wires(MemberRole.class, MemberRole::wire), false));
    }

    private Set<String> allowedValues(String constraintName) {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(con.oid)
                  FROM pg_constraint con
                  JOIN pg_class c ON c.oid = con.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE con.conname = :name AND n.nspname = current_schema()
                """)
                .param("name", constraintName)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError(
                        "no CHECK constraint named " + constraintName + " — was it renamed?"));

        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = LITERAL.matcher(definition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("constraint %s parsed no literals from: %s", constraintName, definition)
                .isNotEmpty();
        return values;
    }

    @ParameterizedTest(name = "{0} matches the database CHECK constraint")
    @MethodSource("alignments")
    void enumMatchesConstraint(Alignment alignment) {
        Set<String> allowed = allowedValues(alignment.constraint());

        if (alignment.exactMatch()) {
            assertThat(allowed)
                    .as("the enum and %s must describe the same set of values", alignment.constraint())
                    .containsExactlyInAnyOrderElementsOf(alignment.enumWires());
        } else {
            assertThat(alignment.enumWires())
                    .as("%s allows values the enum does not model", alignment.constraint())
                    .containsAll(allowed);
        }
    }

    // ------------------------------------------------ values used against the db

    @Test
    @DisplayName("every BinderItemType can actually be written")
    void binderItemTypesAreAccepted() {
        for (BinderItemType type : BinderItemType.values()) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO binder_item (id, project_id, type, title, order_key)
                    VALUES (:id, :p, :type, :title, :key)
                    """)
                    .param("id", id).param("p", projectId).param("type", type.wire())
                    .param("title", type.name()).param("key", "V" + type.ordinal())
                    .update();
            assertThat(jdbc.sql("SELECT type FROM binder_item WHERE id = :id")
                    .param("id", id).query(String.class).single())
                    .isEqualTo(type.wire());
            jdbc.sql("DELETE FROM binder_item WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    @DisplayName("every DevicePlatform can actually be written")
    void devicePlatformsAreAccepted() {
        for (DevicePlatform platform : DevicePlatform.values()) {
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO device (id, user_id, name, platform) VALUES (:id, :u, :n, :p)")
                    .param("id", id).param("u", userId).param("n", platform.name())
                    .param("p", platform.wire())
                    .update();
            assertThat(jdbc.sql("SELECT platform FROM device WHERE id = :id")
                    .param("id", id).query(String.class).single()).isEqualTo(platform.wire());
        }
    }

    @Test
    @DisplayName("every ChangeOp and EntityType can actually be written to the feed")
    void feedValuesAreAccepted() {
        for (EntityType type : EntityType.values()) {
            for (ChangeOp op : ChangeOp.values()) {
                jdbc.sql("""
                        INSERT INTO change_log (project_id, entity_type, entity_id, op, device_id)
                        VALUES (:p, :type, :entity, :op, :d)
                        """)
                        .param("p", projectId).param("type", type.wire())
                        .param("entity", UUID.randomUUID()).param("op", op.wire())
                        .param("d", deviceA)
                        .update();
            }
        }
        assertThat(jdbc.sql("SELECT count(DISTINCT entity_type) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single())
                .isEqualTo(EntityType.values().length);
    }

    @Test
    @DisplayName("every ExportFormat can actually be written")
    void exportFormatsAreAccepted() {
        for (ExportFormat format : ExportFormat.values()) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO compile_preset (id, project_id, name, format, include_query)
                    VALUES (:id, :p, :n, :f, '{}'::jsonb)
                    """)
                    .param("id", id).param("p", projectId).param("n", format.name())
                    .param("f", format.wire())
                    .update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM compile_preset WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single())
                .isEqualTo(ExportFormat.values().length);
    }

    @Test
    @DisplayName("a value outside the enum is rejected by the database")
    void invalidValueIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key)
                VALUES (:id, :p, 'chapter', 'X', 'V')
                """).param("id", UUID.randomUUID()).param("p", projectId).update())
                .as("if this ever passes, the CHECK constraint has been dropped")
                .hasMessageContaining("binder_item_type_valid");
    }
}
