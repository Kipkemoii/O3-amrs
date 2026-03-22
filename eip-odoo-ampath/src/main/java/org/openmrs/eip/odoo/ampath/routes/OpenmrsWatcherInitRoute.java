package org.openmrs.eip.odoo.ampath.routes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Bootstrap the standalone Debezium watcher for this EIP client.
 * Runs once at startup to initialise the OpenMRS DB-event stream.
 *
 * <p>Same pattern as {@code eip-openelis-openmrs}
 * ({@code com.ozonehis.eip.openelis.openmrs.routes.OpenmrsWatcherInitRoute}).
 */
@Component
public class OpenmrsWatcherInitRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("scheduler:openmrs-watcher?initialDelay=500&repeatCount=1")
                .routeId("ampath-openmrs-watcher-init-route")
                .to("openmrs-watcher:init");
    }
}
