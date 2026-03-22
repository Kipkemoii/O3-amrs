package org.openmrs.eip.odoo.ampath.routes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private Set<String> uuidSet = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ff4485a4-f071-4423-aeb2-db6efce52b83", "2315ab24-9a4e-4b36-b189-8e74d2c77394")));


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
