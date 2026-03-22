package org.openmrs.eip.odoo.ampath.routes;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Applies {@code onException} to <strong>all</strong> routes in the Camel context.
 *
 * <p>{@link GenericOrderRouting} previously declared {@code onException} on its own
 * {@link org.apache.camel.builder.RouteBuilder}; in Camel that only covers routes defined
 * in that builder. Upstream {@code fhir-servicerequest-router} (Ozone) still failed with
 * HTTP 404 when FHIR2 has no {@code ServiceRequest} for radiology/procedure orders.</p>
 *
 * <p>When {@code eip.generic.order.type.uuids} is set, we intercept those failures and
 * forward the exchange to {@code direct:ampath-generic-order-listener}.</p>
 */
@Component
public class ServiceRequestFhir404RouteConfiguration extends RouteConfigurationBuilder {

    @Value("${eip.generic.order.type.uuids:}")
    private String genericOrderTypeUuids;

    @Override
    public void configuration() {
        if (!StringUtils.hasText(genericOrderTypeUuids)) {
            return;
        }
        Set<String> uuidSet = Arrays.stream(genericOrderTypeUuids.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (uuidSet.isEmpty()) {
            return;
        }

        routeConfiguration()
                .onException(Exception.class)
                .onWhen(simple(
                        "(${exception.message} contains 'resourceClass=ServiceRequest'"
                                + " || ${exception.message} contains 'Resource of type ServiceRequest'"
                                + " || ${exception.message} contains 'HAPI-1357'"
                                + " || ${exception.message} contains 'HAPI-1361')"
                                + " && (${exception.message} contains 'HTTP 404'"
                                + " || ${exception.message} contains 'is not known')"))
                .handled(true)
                .log(LoggingLevel.WARN,
                        "ServiceRequestFhir404RouteConfiguration: FHIR ServiceRequest read failed for "
                                + "${exchangeProperty.event.identifier} — forwarding to generic order handler")
                .to("direct:ampath-generic-order-listener");
    }
}
