package com.noveltea.config;

/**
 * How many bytes a string becomes on the wire and in the database.
 *
 * <p>Exists because {@code maxDocumentBytes} is a bound in <em>bytes</em> and
 * {@link String#length()} counts UTF-16 code units, which is a different number for every
 * manuscript that is not pure ASCII. Prose is exactly where they diverge: curly quotes,
 * em dashes and accented names are three bytes each and one char each, so measuring with
 * {@code length()} lets a document through at roughly three times the configured ceiling.
 *
 * <p>Counted in one pass rather than via {@code getBytes(UTF_8).length}, which allocates a
 * second copy of the document — up to four times its size — purely to discard it.
 */
public final class Utf8 {

    private Utf8() {}

    /**
     * The UTF-8 byte length of {@code value}, agreeing with
     * {@code value.getBytes(StandardCharsets.UTF_8).length} for every input.
     *
     * <p>Including malformed ones: an unpaired surrogate encodes as the single byte
     * {@code '?'}, because that is what the JDK's replacement does — not the three-byte
     * U+FFFD it is easy to assume. A size check must not become a second parser, and it
     * must not disagree with the encoder that will actually write the bytes; matching it
     * exactly is the only way to be sure of both. Jackson refuses a malformed document
     * long before this, so the case is unreachable in practice and is pinned anyway.
     */
    public static int byteLength(CharSequence value) {
        int bytes = 0;
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(value.charAt(i + 1))) {
                    // One code point spanning two chars: four bytes, and the low half is
                    // consumed here so it is not counted again on its own.
                    bytes += 4;
                    i++;
                } else {
                    bytes += 1;
                }
            } else if (Character.isLowSurrogate(c)) {
                // A low half with no high half before it: the pair case above consumes
                // every legitimate one, so reaching here means it is unpaired.
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
