package com.noveltea.sync.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.model.EntityType;
import com.noveltea.support.AbstractPostgresTest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural guards on {@link SyncEntitySpec}.
 *
 * <p>The behavioural tests prove the validation works today. These prove it cannot quietly
 * stop working: they read the live schema and fail when a spec drifts away from it, rather
 * than waiting for a runtime error to reveal the gap.
 */
class SyncEntitySpecGuardTest extends AbstractPostgresTest {

    /** Matches a single-column membership CHECK, which an ENUM column already covers. */
    private static final Pattern ENUM_CHECK = Pattern.compile("\\((\\w+) = ANY \\(ARRAY\\[");

    private Set<String> checkConstraints(String table) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT con.conname
                  FROM pg_constraint con
                  JOIN pg_class c ON c.oid = con.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE con.contype = 'c' AND c.relname = :table AND n.nspname = current_schema()
                """).param("table", table).query(String.class).list());
    }

    /** Constraint names are unique per table, not per database — scope to this schema. */
    private String definition(String table, String constraint) {
        return jdbc.sql("""
                SELECT pg_get_constraintdef(con.oid)
                  FROM pg_constraint con
                  JOIN pg_class c ON c.oid = con.conrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE con.conname = :name AND c.relname = :table AND n.nspname = current_schema()
                """).param("name", constraint).param("table", table).query(String.class).single();
    }

    private Set<String> columnsOf(String table) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = :table AND table_schema = current_schema()
                """).param("table", table).query(String.class).list());
    }

    @Test
    @DisplayName("GUARD: every CHECK constraint is mirrored by an enum column or a named invariant")
    void everyCheckConstraintIsAccountedFor() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            Set<String> mirrored = new LinkedHashSet<>(
                    spec.invariants().stream().map(SyncEntitySpec.Invariant::mirrorsConstraint).toList());

            for (String constraint : checkConstraints(spec.table())) {
                String def = definition(spec.table(), constraint);
                Matcher enumMatch = ENUM_CHECK.matcher(def);

                boolean coveredByEnum = false;
                if (enumMatch.find()) {
                    String column = enumMatch.group(1);
                    coveredByEnum = spec.column(column)
                            .map(c -> c.kind() == ColumnKind.ENUM)
                            .orElse(false);
                }

                assertThat(coveredByEnum || mirrored.contains(constraint))
                        .as("%s on %s has no counterpart in SyncEntitySpec. Violating it would"
                                + " raise an exception and fail a whole push instead of producing one"
                                + " reported conflict. Add an ENUM column or an Invariant naming this"
                                + " constraint. Definition: %s", constraint, spec.table(), def)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("GUARD: every invariant names a constraint that actually exists")
    void invariantsReferToRealConstraints() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            Set<String> actual = checkConstraints(spec.table());
            for (var invariant : spec.invariants()) {
                assertThat(actual)
                        .as("invariant on %s names %s, which no longer exists — renamed or dropped?",
                                spec.table(), invariant.mirrorsConstraint())
                        .contains(invariant.mirrorsConstraint());
            }
        }
    }

    @Test
    @DisplayName("GUARD: every parent reference points at a project-scoped table")
    void parentReferencesAreProjectScoped() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            for (var ref : spec.parentRefs()) {
                assertThat(columnsOf(ref.table()))
                        .as("%s.%s points at %s, which has no project_id. The same-project check"
                                + " is an authorization boundary: without a column to scope on, a"
                                + " caller could attach a row to another account's data.",
                                spec.table(), ref.column(), ref.table())
                        .contains("project_id");
            }
        }
    }

    @Test
    @DisplayName("GUARD: every declared column and parent reference exists in the table")
    void declaredColumnsExist() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            Set<String> actual = columnsOf(spec.table());
            assertThat(actual).as("table %s does not exist", spec.table()).isNotEmpty();

            for (ColumnSpec column : spec.columns()) {
                assertThat(actual)
                        .as("%s declares column %s, which the table does not have",
                                spec.table(), column.column())
                        .contains(column.column());
            }
            for (var ref : spec.parentRefs()) {
                assertThat(actual)
                        .as("%s declares parent column %s, which the table does not have",
                                spec.table(), ref.column())
                        .contains(ref.column());
            }
            if (spec.hasProjectId()) {
                assertThat(actual).as("%s is declared project-scoped", spec.table()).contains("project_id");
            }
        }
    }

    @Test
    @DisplayName("GUARD: every synced table carries the columns the mutation contract needs")
    void syncedTablesSupportTheMutationContract() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            assertThat(columnsOf(spec.table()))
                    .as("%s is synced, so it must carry version and device attribution", spec.table())
                    .contains("version", "updated_by_device_id");
        }
    }

    @Test
    @DisplayName("GUARD: every EntityType is either spec-driven or deliberately hand-written")
    void everyEntityTypeIsHandled() {
        // Documents and binder items carry conflict copies and tree semantics that no
        // declarative spec should own. project_member is read-only in Core.
        List<EntityType> handledElsewhere =
                List.of(EntityType.DOCUMENT, EntityType.BINDER_ITEM, EntityType.PROJECT_MEMBER);

        for (EntityType type : EntityType.values()) {
            boolean spec = SyncEntitySpec.forType(type).isPresent();
            assertThat(spec || handledElsewhere.contains(type))
                    .as("%s has no SyncEntitySpec and is not in the hand-written list. A push of it"
                            + " would report not_implemented forever. Add a spec, or add it here"
                            + " with a reason.", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("GUARD: spec tables match the EntityType table names")
    void specTablesMatchEntityTypes() {
        for (SyncEntitySpec spec : SyncEntitySpec.all()) {
            assertThat(spec.table())
                    .as("spec for %s disagrees with the enum about its table", spec.type())
                    .isEqualTo(spec.type().table());
        }
    }
}
