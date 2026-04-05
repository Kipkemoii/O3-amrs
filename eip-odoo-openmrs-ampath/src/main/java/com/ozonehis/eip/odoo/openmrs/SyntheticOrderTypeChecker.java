package com.ozonehis.eip.odoo.openmrs;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("syntheticOrderTypeChecker")
public class SyntheticOrderTypeChecker {

    @Autowired
    private SyntheticOrderProperties properties;

    @SuppressWarnings("unchecked")
    public boolean matchesOrderTypeRow(Exchange exchange) {
        if (!properties.isEnabled()) {
            return false;
        }
        Object body = exchange.getMessage().getBody();
        if (!(body instanceof List) || ((List<?>) body).isEmpty()) {
            return false;
        }
        Object row = ((List<?>) body).get(0);
        if (!(row instanceof Map)) {
            return false;
        }
        Object uuid = ((Map<?, ?>) row).get("uuid");
        if (!(uuid instanceof String)) {
            return false;
        }
        return properties.getOrderTypeUuids().contains(uuid);
    }
}
