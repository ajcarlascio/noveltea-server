package com.noveltea.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These assert the ORDERING PROPERTY, not the specific strings produced. A test that
 * pinned exact output would pass for any self-consistent implementation, including a
 * broken one.
 */
class FractionalIndexTest {

    @Test
    @DisplayName("result always sorts strictly between its bounds")
    void betweenIsStrictlyBetween() {
        String a = FractionalIndex.first();
        String b = FractionalIndex.between(a, null);
        String mid = FractionalIndex.between(a, b);

        assertThat(a).isLessThan(mid);
        assertThat(mid).isLessThan(b);
    }

    @Test
    @DisplayName("1000 repeated insertions between the same pair never collide or lose order")
    void survivesRepeatedInsertionBetweenSamePair() {
        // This is the case float-based indexing dies on after ~50 iterations.
        String low = FractionalIndex.first();
        String high = FractionalIndex.between(low, null);

        List<String> generated = new ArrayList<>();
        String cursor = high;
        for (int i = 0; i < 1000; i++) {
            cursor = FractionalIndex.between(low, cursor);
            assertThat(cursor).isGreaterThan(low);
            generated.add(cursor);
        }
        assertThat(generated).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a randomly built list stays sorted under string comparison")
    void randomInsertionsRemainSorted() {
        Random random = new Random(1234);
        List<String> keys = new ArrayList<>();
        keys.add(FractionalIndex.first());

        for (int i = 0; i < 300; i++) {
            int at = random.nextInt(keys.size() + 1);
            String before = at == 0 ? null : keys.get(at - 1);
            String after = at == keys.size() ? null : keys.get(at);
            keys.add(at, FractionalIndex.between(before, after));
        }

        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        assertThat(keys).as("insertion order must equal lexicographic order").isEqualTo(sorted);
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("keys ending in '0' are rejected, and prefix-extended neighbours still work")
    void handlesPrefixExtendedNeighbours() {
        assertThatThrownBy(() -> FractionalIndex.between("a0", "a0V"))
                .as("'a0' ends in 0 and must be rejected outright")
                .isInstanceOf(IllegalArgumentException.class);

        String mid = FractionalIndex.between("a1", "a1V");
        assertThat(mid).isGreaterThan("a1");
        assertThat(mid).isLessThan("a1V");
    }

    @Test
    @DisplayName("rejects inverted or equal bounds")
    void rejectsBadBounds() {
        assertThatThrownBy(() -> FractionalIndex.between("V", "V"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FractionalIndex.between("b", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
