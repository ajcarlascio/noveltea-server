package com.noveltea.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The enums themselves: lookup behaviour and the shape of their wire forms.
 *
 * <p>Driven off the enum constants rather than a hand-written list, so a new value is
 * covered the moment it is added instead of quietly escaping these checks.
 */
class WireEnumTest {

    /** @param wire how the value is written in JSON and stored in its text column */
    record EnumUnderTest<E extends Enum<E>>(
            Class<E> type, Function<E, String> wire, Function<String, Optional<E>> lookup) {
        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    static Stream<EnumUnderTest<?>> allEnums() {
        return Stream.of(
                new EnumUnderTest<>(EntityType.class, EntityType::wire, EntityType::fromWire),
                new EnumUnderTest<>(ChangeOp.class, ChangeOp::wire, ChangeOp::fromWire),
                new EnumUnderTest<>(BinderItemType.class, BinderItemType::wire, BinderItemType::fromWire),
                new EnumUnderTest<>(TaxonomyKind.class, TaxonomyKind::wire, TaxonomyKind::fromWire),
                new EnumUnderTest<>(MetadataFieldType.class, MetadataFieldType::wire, MetadataFieldType::fromWire),
                new EnumUnderTest<>(ExportFormat.class, ExportFormat::wire, ExportFormat::fromWire),
                new EnumUnderTest<>(DevicePlatform.class, DevicePlatform::wire, DevicePlatform::fromWire),
                new EnumUnderTest<>(MemberRole.class, MemberRole::wire, MemberRole::fromWire));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> wires(EnumUnderTest<?> subject) {
        return Arrays.stream(subject.type().getEnumConstants())
                .map(constant -> ((Function) subject.wire()).apply(constant).toString())
                .toList();
    }

    @ParameterizedTest(name = "{0}: every constant round-trips through its wire form")
    @MethodSource("allEnums")
    <E extends Enum<E>> void roundTripsEveryConstant(EnumUnderTest<E> subject) {
        for (E constant : subject.type().getEnumConstants()) {
            String wire = subject.wire().apply(constant);
            assertThat(subject.lookup().apply(wire))
                    .as("%s should resolve back to %s", wire, constant)
                    .contains(constant);
        }
    }

    @ParameterizedTest(name = "{0}: wire forms are unique, lowercase and non-blank")
    @MethodSource("allEnums")
    void wireFormsAreWellFormed(EnumUnderTest<?> subject) {
        List<String> wires = wires(subject);
        assertThat(wires).doesNotHaveDuplicates();
        assertThat(wires).allSatisfy(wire -> {
            assertThat(wire).isNotBlank();
            assertThat(wire)
                    .as("lookup lowercases its input, so a non-lowercase constant could never match")
                    .isEqualTo(wire.toLowerCase(Locale.ROOT));
        });
    }

    @ParameterizedTest(name = "{0}: lookup tolerates casing and surrounding whitespace")
    @MethodSource("allEnums")
    <E extends Enum<E>> void lookupNormalisesInput(EnumUnderTest<E> subject) {
        E first = subject.type().getEnumConstants()[0];
        String wire = subject.wire().apply(first);

        assertThat(subject.lookup().apply(wire.toUpperCase(Locale.ROOT))).contains(first);
        assertThat(subject.lookup().apply("  " + wire + "  ")).contains(first);
    }

    @ParameterizedTest(name = "{0}: unknown and null inputs return empty rather than throwing")
    @MethodSource("allEnums")
    void unknownInputIsEmpty(EnumUnderTest<?> subject) {
        assertThat(subject.lookup().apply(null)).isEmpty();
        assertThat(subject.lookup().apply("")).isEmpty();
        assertThat(subject.lookup().apply("   ")).isEmpty();
        assertThat(subject.lookup().apply("definitely-not-a-value")).isEmpty();
        assertThat(subject.lookup().apply("'; DROP TABLE project; --")).isEmpty();
    }

    @Test
    @DisplayName("EntityType table names are unique and safe to interpolate")
    void entityTypeTablesAreDistinctIdentifiers() {
        List<String> tables = Arrays.stream(EntityType.values()).map(EntityType::table).toList();
        assertThat(tables).doesNotHaveDuplicates();
        assertThat(tables).allSatisfy(table -> assertThat(table)
                .as("table names are interpolated into SQL, so they must be bare identifiers")
                .matches("[a-z][a-z0-9_]*"));
    }
}
