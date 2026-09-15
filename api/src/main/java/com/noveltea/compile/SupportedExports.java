package com.noveltea.compile;

import com.noveltea.model.ExportFormat;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every format this installation can produce, from every {@link ExportProvider} present.
 *
 * <p>Exists because the extension point did not previously admit a second implementation.
 * Core's own provider is an ordinary bean, and the callers asked for a single
 * {@code ExportProvider}; the moment a commercial module supplied one of its own, Spring
 * could not choose between them and the application failed to start with
 * {@code NoUniqueBeanDefinitionException}. An extension point that breaks on being
 * extended is not an extension point, and the failure would have arrived at the worst
 * possible moment — on somebody's first paid build, as a startup crash with nothing in the
 * message about editions.
 *
 * <p><b>The union, not an override.</b> A commercial module <em>adds</em> formats; it does
 * not replace Core's. Making it a replacement would put the three formats every
 * self-hoster relies on inside the bean a licence swaps out, so a mistake there would take
 * away what Core promises rather than merely failing to add to it.
 *
 * <p>Deliberately not an {@code ExportProvider} itself. Aggregating a list that would then
 * contain this bean is a self-reference Spring resolves only by convention, and the class
 * that decides what is available should not also be something a module can register.
 */
@Component
public class SupportedExports {

    private final Set<ExportFormat> formats;

    public SupportedExports(List<ExportProvider> providers) {
        Set<ExportFormat> union = EnumSet.noneOf(ExportFormat.class);
        for (ExportProvider provider : providers) {
            union.addAll(provider.supportedFormats());
        }
        this.formats = Set.copyOf(union);
    }

    public boolean supports(ExportFormat format) {
        return formats.contains(format);
    }

    public Set<ExportFormat> all() {
        return formats;
    }
}
