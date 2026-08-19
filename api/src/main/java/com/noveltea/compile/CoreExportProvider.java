package com.noveltea.compile;

import com.noveltea.model.ExportFormat;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The formats Core ships, matching {@code CORE_FORMATS} in {@code @noveltea/compile}.
 *
 * <p>All three derive from the same HTML serializer, which is why that serializer lives in
 * Core regardless of which formats an installation can produce.
 */
@Component
public class CoreExportProvider implements ExportProvider {

    private static final Set<ExportFormat> SUPPORTED =
            EnumSet.of(ExportFormat.TXT, ExportFormat.MD, ExportFormat.HTML);

    @Override
    public Set<ExportFormat> supportedFormats() {
        return SUPPORTED;
    }
}
