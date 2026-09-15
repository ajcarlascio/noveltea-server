package com.noveltea.compile;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.model.CompileDestination;
import com.noveltea.model.ExportFormat;
import com.noveltea.support.AbstractPostgresTest;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Core's extension points, exercised the way a commercial build uses them.
 *
 * <p>The providers registered below stand in for the private module: they contribute
 * nothing but a set of enum values that already exist in Core, so no commercial code is
 * involved — only the registration mechanism, which is Core's to keep working.
 *
 * <p><b>This is the test that did not exist.</b> Both extension points were documented as
 * the seam a commercial module plugs into, and both were injected as a single bean, so
 * supplying a second implementation failed the application at startup with
 * {@code NoUniqueBeanDefinitionException}. Nothing caught it, because Core is the only
 * build that ever ran, and Core registers exactly one of each. The architecture document
 * warns that retrofitting an extension point around merged code is the expensive path;
 * this asserts the seam works <em>before</em> anything is riding on it.
 */
@Import({ExtensionPointTest.SecondProviders.class})
class ExtensionPointTest extends AbstractPostgresTest {

    @TestConfiguration
    static class SecondProviders {
        @Bean
        ExportProvider extraExportProvider() {
            return () -> EnumSet.of(ExportFormat.DOCX, ExportFormat.EPUB);
        }

        @Bean
        DestinationProvider extraDestinationProvider() {
            return () -> EnumSet.of(CompileDestination.CLOUD);
        }
    }

    @Autowired SupportedExports exports;
    @Autowired SupportedDestinations destinations;

    @Test
    @DisplayName("a second export provider starts the application rather than breaking it")
    void secondExportProviderIsAccepted() {
        // Reaching this assertion at all is most of the test: the context had to build.
        assertThat(exports.supports(ExportFormat.DOCX)).isTrue();
        assertThat(exports.supports(ExportFormat.EPUB)).isTrue();
    }

    @Test
    @DisplayName("the second provider adds formats, it does not replace Core's")
    void coreFormatsSurviveAnotherProvider() {
        // The three formats every self-hoster relies on must not live inside the bean a
        // licence swaps out. Union, never override.
        assertThat(exports.supports(ExportFormat.TXT)).isTrue();
        assertThat(exports.supports(ExportFormat.MD)).isTrue();
        assertThat(exports.supports(ExportFormat.HTML)).isTrue();
    }

    @Test
    @DisplayName("a format nobody registered is still unavailable")
    void unregisteredFormatsStayUnavailable() {
        // The union must not degrade into "everything is available", which would turn the
        // 501 an unlicensed build owes into a job that fails in the worker instead.
        assertThat(exports.supports(ExportFormat.RTF)).isFalse();
        assertThat(exports.all()).doesNotContain(ExportFormat.RTF);
    }

    @Test
    @DisplayName("destinations extend the same way")
    void destinationsAggregateToo() {
        assertThat(destinations.supports(CompileDestination.CLOUD)).isTrue();
        assertThat(destinations.supports(CompileDestination.DOWNLOAD)).isTrue();
        assertThat(destinations.supports(CompileDestination.SERVER)).isTrue();
    }
}
