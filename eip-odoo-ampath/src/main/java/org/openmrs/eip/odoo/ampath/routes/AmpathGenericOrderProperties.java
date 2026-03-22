package org.openmrs.eip.odoo.ampath.routes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves generic order type UUIDs from the {@code EIP_GENERIC_ORDER_TYPE_UUIDS}
 * environment variable via Spring {@code @Value}. Falls back to the standard
 * Radiology + Procedure UUIDs if the variable is not set.
 */
@Component
public class AmpathGenericOrderProperties {

    private static final Logger log = LoggerFactory.getLogger(AmpathGenericOrderProperties.class);

    /**
     * Read the env var directly by name (avoids Spring relaxed-binding issues
     * with deeply-dotted property names in some container runtimes).
     * Default = standard Radiology + Procedure UUIDs.
     */
    @Value("${EIP_GENERIC_ORDER_TYPE_UUIDS:ff4485a4-f071-4423-aeb2-db6efce52b83,2315ab24-9a4e-4b36-b189-8e74d2c77394}")
    private String genericOrderTypeUuidsFromSpring;

    private Set<String> uuidSet = Collections.emptySet();

    @PostConstruct
    void resolve() {
        log.info("Raw EIP_GENERIC_ORDER_TYPE_UUIDS value from Spring: '{}'", genericOrderTypeUuidsFromSpring);

        if (StringUtils.hasText(genericOrderTypeUuidsFromSpring)) {
            uuidSet = Arrays.stream(genericOrderTypeUuidsFromSpring.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
        }

        log.info("GenericOrderRouting resolved {} order-type UUIDs: {}", uuidSet.size(), uuidSet);
    }

    public boolean isGenericOrderHandlingEnabled() {
        return !uuidSet.isEmpty();
    }

    public Set<String> getGenericOrderTypeUuids() {
        return uuidSet;
    }

    public String getEffectiveRawUuids() {
        return genericOrderTypeUuidsFromSpring;
    }
}
