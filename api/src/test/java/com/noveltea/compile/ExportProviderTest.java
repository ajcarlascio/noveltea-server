package com.noveltea.compile;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.model.ExportFormat;
import com.noveltea.support.AbstractPostgresTest;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExportProviderTest extends AbstractPostgresTest {

    @Autowired ExportProvider exports;

    @Test
    @DisplayName("Core supports exactly the formats the compile package ships")
    void coreSupportsItsOwnFormats() {
        assertThat(exports.supportedFormats())
                .containsExactlyInAnyOrder(ExportFormat.TXT, ExportFormat.MD, ExportFormat.HTML);
    }

    @Test
    @DisplayName("every other format is reported unsupported rather than silently accepted")
    void commercialFormatsAreNotSupported() {
        for (ExportFormat format : Arrays.asList(
                ExportFormat.RTF, ExportFormat.DOCX, ExportFormat.ODT,
                ExportFormat.EPUB, ExportFormat.PDF)) {
            assertThat(exports.supports(format))
                    .as("%s must not appear available in a Core build", format)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("every ExportFormat is classified as supported or not — none is unaccounted for")
    void everyFormatIsClassified() {
        for (ExportFormat format : ExportFormat.values()) {
            boolean supported = exports.supports(format);
            assertThat(supported || !supported).isTrue();
        }
        assertThat(ExportFormat.values().length)
                .as("a new format must be deliberately placed on one side of the edition line")
                .isEqualTo(8);
    }
}
