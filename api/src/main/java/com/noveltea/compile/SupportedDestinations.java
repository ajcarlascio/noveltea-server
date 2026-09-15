package com.noveltea.compile;

import com.noveltea.model.CompileDestination;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every destination this installation offers, from every {@link DestinationProvider}.
 *
 * <p>The same shape, and the same reason, as {@link SupportedExports}: a commercial module
 * contributing cloud storage must be able to register a second provider without the
 * application refusing to start. See that class for the argument; this is its counterpart
 * so the two extension points cannot drift into behaving differently.
 */
@Component
public class SupportedDestinations {

    private final Set<CompileDestination> destinations;

    public SupportedDestinations(List<DestinationProvider> providers) {
        Set<CompileDestination> union = EnumSet.noneOf(CompileDestination.class);
        for (DestinationProvider provider : providers) {
            union.addAll(provider.supportedDestinations());
        }
        this.destinations = Set.copyOf(union);
    }

    public boolean supports(CompileDestination destination) {
        return destinations.contains(destination);
    }

    public Set<CompileDestination> all() {
        return destinations;
    }
}
