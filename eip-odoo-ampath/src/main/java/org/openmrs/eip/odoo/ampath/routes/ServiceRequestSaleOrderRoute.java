package org.openmrs.eip.odoo.ampath.routes;

import com.ozonehis.eip.odoo.openmrs.processors.ServiceRequestProcessor;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Defines {@code direct:service-request-to-sale-order-processor} for this standalone client.
 *
 * <p>The stock {@code eip-odoo-openmrs} {@code ServiceRequestRouting} class is excluded by the
 * Maven Shade filter, so we need our own route definition that invokes {@link ServiceRequestProcessor}.
 * This processor handles:
 * <ul>
 *   <li>Looking up / creating the Odoo partner (customer) for the patient</li>
 *   <li>Finding or creating a single draft Sale Order (quotation) per visit</li>
 *   <li>Adding sale order lines for each order</li>
 *   <li>Populating custom fields (patient weight, DOB, customer ID) on first creation</li>
 * </ul>
 */
@Component
public class ServiceRequestSaleOrderRoute extends RouteBuilder {

    @Autowired
    private ServiceRequestProcessor serviceRequestProcessor;

    @Override
    public void configure() {
        from("direct:service-request-to-sale-order-processor")
                .routeId("ampath-service-request-to-sale-order-processor")
                .process(serviceRequestProcessor)
                .log(LoggingLevel.INFO, "AMPATH: Processed ServiceRequest → Odoo Sale Order")
                .end();
    }
}
