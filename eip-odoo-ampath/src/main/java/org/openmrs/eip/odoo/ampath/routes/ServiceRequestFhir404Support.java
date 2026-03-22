package org.openmrs.eip.odoo.ampath.routes;

import org.apache.camel.Exchange;

/**
 * Detects FHIR client failures for {@code ServiceRequest} read-by-id (typical when FHIR2
 * does not map radiology/procedure OpenMRS orders to ServiceRequest).
 */
final class ServiceRequestFhir404Support {

    private ServiceRequestFhir404Support() {}

    /**
     * True when the exchange carries a failure that looks like "no such ServiceRequest".
     */
    static boolean isServiceRequestReadNotFound(Exchange exchange) {
        Throwable t = exchange.getException();
        if (t == null) {
            t = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        }
        while (t != null) {
            String m = t.getMessage();
            if (m != null) {
                boolean aboutSr = m.contains("resourceClass=ServiceRequest")
                        || m.contains("Resource of type ServiceRequest")
                        || m.contains("HAPI-1357")
                        || m.contains("HAPI-1361");
                boolean notFound = m.contains("HTTP 404")
                        || m.contains("is not known")
                        || m.contains("ResourceNotFoundException");
                if (aboutSr && notFound) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Whether this failure should be retried via {@code direct:ampath-generic-order-listener}.
     * The FHIR read runs on a worker thread; {@link Exchange#getFromRouteId()} is not always set.
     */
    static boolean shouldDivertToGenericOrderHandler(Exchange exchange) {
        if (!isServiceRequestReadNotFound(exchange)) {
            return false;
        }
        String from = exchange.getFromRouteId();
        if (from != null && from.toLowerCase().contains("servicerequest")) {
            return true;
        }
        Object evt = exchange.getProperty("event");
        return evt != null && evt.toString().contains("tableName=orders");
    }
}
