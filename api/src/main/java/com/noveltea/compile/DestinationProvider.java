package com.noveltea.compile;

import com.noveltea.model.CompileDestination;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which destinations this installation offers.
 *
 * <p>The same extension shape as {@link ExportProvider}: Core offers what a self-hosted
 * install can do on its own, and a commercial module contributes cloud storage by
 * replacing this bean. Core must keep working with only its own implementation.
 */
public interface DestinationProvider {

    Set<CompileDestination> supportedDestinations();

    default boolean supports(CompileDestination destination) {
        return supportedDestinations().contains(destination);
    }

    @Component
    class Core implements DestinationProvider {
        private static final Set<CompileDestination> SUPPORTED =
                EnumSet.of(CompileDestination.DOWNLOAD, CompileDestination.SERVER);

        @Override
        public Set<CompileDestination> supportedDestinations() {
            return SUPPORTED;
        }
    }
}
