package com.noveltea.compile;

import com.noveltea.model.ExportFormat;
import java.util.Set;

/**
 * Which export formats this installation can actually produce.
 *
 * <p>The extension point between Core and a commercial build. Core registers the formats
 * it ships; a commercial module contributes the rest by supplying another implementation.
 * Core must keep working with only its own provider present, so nothing may assume a
 * format is available without asking.
 */
public interface ExportProvider {

    Set<ExportFormat> supportedFormats();

    default boolean supports(ExportFormat format) {
        return supportedFormats().contains(format);
    }
}
