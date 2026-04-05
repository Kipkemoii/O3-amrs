package com.ozonehis.eip.odoo.openmrs;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenMRS {@code order_type.uuid} values that use OpenMRS REST + synthetic FHIR ServiceRequest
 * (see {@link com.ozonehis.eip.odoo.openmrs.routes.RestSyntheticServiceRequestRoute}). Configure via
 * {@code eip.order.type.uuids} or env {@code EIP_ORDER_TYPE_UUIDS}.
 */
@Component
public class SyntheticOrderProperties {

    @Value("${eip.order.type.uuids:}")
    private String rawUuids;

    private Set<String> orderTypeUuids = Set.of();

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(rawUuids)) {
            orderTypeUuids = Set.of();
            return;
        }
        Set<String> set = new HashSet<>();
        for (String part : rawUuids.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        orderTypeUuids = Collections.unmodifiableSet(set);
    }

    public boolean isEnabled() {
        return !orderTypeUuids.isEmpty();
    }

    public Set<String> getOrderTypeUuids() {
        return orderTypeUuids;
    }
}
