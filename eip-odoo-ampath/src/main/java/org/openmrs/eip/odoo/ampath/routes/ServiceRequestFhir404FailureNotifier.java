package org.openmrs.eip.odoo.ampath.routes;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Handles ServiceRequest FHIR read failures for {@code orders} events: upstream
 * {@code fhir-servicerequest-router} uses a local error handler, so global route-configuration
 * {@code onException} is unreliable. Camel Simple {@code onWhen} with parentheses also fails to
 * parse on some versions, so we avoid route-configuration entirely.
 *
 * <p>Reacts to {@link CamelEvent.ExchangeFailedEvent} and forwards a copy of the exchange to
 * {@code direct:ampath-generic-order-listener}. The original failure may still be logged once;
 * Odoo processing can proceed.</p>
 */
@Component
public class ServiceRequestFhir404FailureNotifier extends EventNotifierSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceRequestFhir404FailureNotifier.class);

    private static final String PROP_DIVERTED = "ampath.fhir404.diverted";

    @Autowired
    private AmpathGenericOrderProperties genericOrderProperties;

    @Autowired
    private CamelContext camelContext;

    private volatile ProducerTemplate producerTemplate;

    @Override
    public boolean isEnabled(CamelEvent event) {
        return event instanceof CamelEvent.ExchangeFailedEvent;
    }

    @Override
    public void notify(CamelEvent event) throws Exception {
        if (!(event instanceof CamelEvent.ExchangeFailedEvent)) {
            return;
        }
        if (!genericOrderProperties.isGenericOrderHandlingEnabled()) {
            return;
        }
        Exchange ex = ((CamelEvent.ExchangeFailedEvent) event).getExchange();
        if (Boolean.TRUE.equals(ex.getProperty(PROP_DIVERTED))) {
            return;
        }
        if (!ServiceRequestFhir404Support.shouldDivertToGenericOrderHandler(ex)) {
            return;
        }

        ex.setProperty(PROP_DIVERTED, true);
        LOG.warn(
                "ServiceRequestFhir404FailureNotifier: forwarding order {} to generic handler after FHIR ServiceRequest read failure",
                ex.getProperty("event") != null ? ex.getProperty("event") : ex.getProperty("event.identifier"));

        ProducerTemplate pt = producerTemplate();
        pt.send("direct:ampath-generic-order-listener", copy -> {
            copy.getMessage().copyFrom(ex.getMessage());
            ex.getProperties().forEach(copy::setProperty);
            copy.setException(null);
            copy.removeProperty(Exchange.EXCEPTION_CAUGHT);
        });
    }

    private ProducerTemplate producerTemplate() {
        ProducerTemplate pt = producerTemplate;
        if (pt == null) {
            synchronized (this) {
                pt = producerTemplate;
                if (pt == null) {
                    pt = camelContext.createProducerTemplate();
                    producerTemplate = pt;
                }
            }
        }
        return pt;
    }
}
