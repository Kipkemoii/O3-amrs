package org.openmrs.eip.odoo.ampath.routes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves generic order type UUIDs from Spring properties and, if missing, from
 * {@code EIP_GENERIC_ORDER_TYPE_UUIDS} in the environment (some deployments do not bind
 * dotted keys the same way as docker-compose).
 */
@Component
public class AmpathGenericOrderProperties {

    @Value("${eip.generic.order.type.uuids:}")
    private String genericOrderTypeUuidsFromSpring;

    private String effectiveRawUuids;
    private Set<String> uuidSet = Collections.emptySet();

    @PostConstruct
    void resolve() {
        String raw = genericOrderTypeUuidsFromSpring;
        if (!StringUtils.hasText(raw)) {
            raw = System.getenv("EIP_GENERIC_ORDER_TYPE_UUIDS");
        }
        
        // Final fallback: Use the standard UUIDs for Radiology and Procedure orders
        if (!StringUtils.hasText(raw)) {
            raw = "ff4485a4-f071-4423-aeb2-db6efce52b83,2315ab24-9a4e-4b36-b189-8e74d2c77394"; 
        }

        effectiveRawUuids = raw;
        uuidSet = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    public boolean isGenericOrderHandlingEnabled() {
        return !uuidSet.isEmpty();
    }

    public Set<String> getGenericOrderTypeUuids() {
        return uuidSet;
    }

    public String getEffectiveRawUuids() {
        return effectiveRawUuids;
    }
}
