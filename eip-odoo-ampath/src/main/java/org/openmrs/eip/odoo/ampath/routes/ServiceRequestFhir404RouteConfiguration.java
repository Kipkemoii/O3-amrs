package org.openmrs.eip.odoo.ampath.routes;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Nameless route configuration: applies {@code onException} to routes that do <strong>not</strong>
 * declare their own {@code errorHandler}. Upstream {@code fhir-servicerequest-router} often sets
 * a local error handler, so {@link ServiceRequestFhir404FailureNotifier} is still required.
 */
@Component
public class ServiceRequestFhir404RouteConfiguration extends RouteConfigurationBuilder {

    @Autowired
    private AmpathGenericOrderProperties genericOrderProperties;

    @Override
    public void configuration() {
        if (!genericOrderProperties.isGenericOrderHandlingEnabled()) {
            return;
        }

        routeConfiguration()
                .onException(Exception.class)
                .onWhen(ServiceRequestFhir404Support::shouldDivertToGenericOrderHandler)
                .handled(true)
                .log(LoggingLevel.WARN,
                        "ServiceRequestFhir404RouteConfiguration: FHIR ServiceRequest read failed for "
                                + "${exchangeProperty.event.identifier} — forwarding to generic order handler")
                .to("direct:ampath-generic-order-listener");
    }
}
