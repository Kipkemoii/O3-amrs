package org.openmrs.eip.odoo.ampath.routes;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Intercepts every producer send to {@code direct:fhir-handler-servicerequest} (the entry consumed
 * by upstream {@code ServiceRequestRouter}) before the message reaches that route.
 *
 * <p>Use this for cross-cutting behavior (logging, headers, metrics). To <em>replace</em> the
 * default handler for some exchanges, use {@code skipSendToOriginalEndpoint()} and {@code to(...)}
 * inside the intercept DSL — only when you have a safe predicate (avoid diverting all
 * {@code orders} events).</p>
 *
 * <p>Enable debug logging with {@code eip.ampath.servicerequest.intercept.log=true}.</p>
 */
@Component
public class FhirHandlerServiceRequestInterceptRouting extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FhirHandlerServiceRequestInterceptRouting.class);

    static final String HANDLER_URI = "direct:fhir-handler-servicerequest";

    @Value("${eip.ampath.servicerequest.intercept.log:false}")
    private boolean logIntercept;

    @Override
    public void configure() {
        interceptSendToEndpoint(HANDLER_URI)
                .process(exchange -> {
                    if (!logIntercept) {
                        return;
                    }
                    Object event = exchange.getProperty("event");
                    LOG.debug("intercept {}: event={}", HANDLER_URI, event);
                });
    }
}
