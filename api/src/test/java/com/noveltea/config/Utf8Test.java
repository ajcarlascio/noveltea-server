package com.noveltea.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checked against the JDK encoder rather than against hand-counted numbers, because the
 * only property that matters is that the two agree: this exists to avoid allocating the
 * byte array, not to have an opinion about encoding.
 */
class Utf8Test {

    private void agreesWithTheEncoder(String value) {
        assertThat(Utf8.byteLength(value))
                .as("byteLength(%s)", value)
                .isEqualTo(value.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("agrees with the JDK across every width UTF-8 uses")
    void agreesAcrossWidths() {
        agreesWithTheEncoder("");
        agreesWithTheEncoder("plain ascii prose");
        agreesWithTheEncoder("éèê");                 // two bytes each
        agreesWithTheEncoder("— “quoted” …");    // em dash, curly quotes, ellipsis
        agreesWithTheEncoder("中文");                        // three bytes each
        agreesWithTheEncoder("😀🌍");            // surrogate pairs, four bytes each
        agreesWithTheEncoder("mixed — 中 😀 end");
    }

    @Test
    @DisplayName("prose is heavier in bytes than String.length() reports")
    void charactersAreNotBytes() {
        // The reason this class exists. Measured with length(), a manuscript of typographic
        // punctuation passes a bytes-named limit at a fraction of its real weight.
        String typographic = "“It’s over,” she said — and meant it…";

        assertThat(Utf8.byteLength(typographic)).isGreaterThan(typographic.length());
        agreesWithTheEncoder(typographic);
    }

    @Test
    @DisplayName("an unpaired surrogate is counted, not thrown on")
    void unpairedSurrogateIsCounted() {
        // A size check must not become a second parser: whatever this is, it has a length.
        // The JDK substitutes a single '?' byte, not the three-byte U+FFFD it is easy to
        // assume — so this is pinned rather than reasoned about.
        agreesWithTheEncoder("a\uD83Db");           // high half, nothing after it
        agreesWithTheEncoder("a\uDE00b");           // low half, nothing before it
        agreesWithTheEncoder("\uD83D");             // the whole string is half a pair
        agreesWithTheEncoder("\uDE00\uD83D");       // the halves, in the wrong order
        assertThat(Utf8.byteLength("a\uD83Db")).isEqualTo(3);
    }
}
