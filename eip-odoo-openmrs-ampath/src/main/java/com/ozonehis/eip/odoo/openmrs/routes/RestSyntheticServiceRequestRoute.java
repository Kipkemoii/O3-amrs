package com.ozonehis.eip.odoo.openmrs.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

import com.ozonehis.eip.odoo.openmrs.SyntheticOrderProperties;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenMRS REST → synthetic {@link ServiceRequest} bundle → {@code ServiceRequestProcessor} (Ozone Odoo pipeline).
 */
@Component
public class RestSyntheticServiceRequestRoute extends RouteBuilder {

    @Autowired
    private SyntheticOrderProperties properties;

    @Value("${eip.fhir.serverUrl}")
    private String fhirServerUrl;

    @Override
    public void configure() {
        final Set<String> uuidSet = properties.getOrderTypeUuids();
        log.info("REST synthetic ServiceRequest route registered (order types: {})", uuidSet);

        final String openmrsRestBase = fhirServerUrl.replaceAll("/ws/fhir2/[^/]+$", "");
        final String orderEndpoint = openmrsRestBase + "/ws/rest/v1/order";
        final ObjectMapper mapper = new ObjectMapper();
        final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};

        from("direct:ampath-rest-synthetic-servicerequest")
                .routeId("ampath-rest-synthetic-servicerequest")
                .log(LoggingLevel.DEBUG, "Synthetic ServiceRequest for order ${exchangeProperty.event.identifier}")
                .setProperty("ampath.order_uuid", simple("${exchangeProperty.event.identifier}"))
                .filter(exchangeProperty("ampath.order_uuid").isNotNull())
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setBody(constant(""))
                .toD(orderEndpoint + "/${exchangeProperty.ampath.order_uuid}")
                .process(exchange -> {
                    String json = exchange.getMessage().getBody(String.class);
                    if (!StringUtils.hasText(json)) {
                        throw new IllegalArgumentException(
                                "Empty OpenMRS order REST response for " + exchange.getProperty("ampath.order_uuid"));
                    }
                    Map<String, Object> order = mapper.readValue(json, mapType);

                    String orderTypeUuid = extractOrderTypeUuid(order);
                    exchange.setProperty("synthetic.order_type_uuid", orderTypeUuid);
                    exchange.setProperty(
                            "synthetic.type_ok",
                            orderTypeUuid != null && uuidSet.contains(orderTypeUuid));

                    exchange.setProperty("synthetic.voided", parseVoided(order));
                    exchange.setProperty("synthetic.order_action", parseOrderAction(order));
                    parseConceptPatientEncounter(order, exchange);

                    exchange.setProperty("synthetic.completed", parseCompleted(order));

                    StringBuilder missing = new StringBuilder();
                    if (exchange.getProperty("synthetic.concept_uuid") == null) {
                        missing.append("concept ");
                    }
                    if (exchange.getProperty("synthetic.patient_uuid") == null) {
                        missing.append("patient ");
                    }
                    if (exchange.getProperty("synthetic.encounter_uuid") == null) {
                        missing.append("encounter ");
                    }
                    exchange.setProperty("synthetic.invalid", missing.length() > 0);
                    exchange.setProperty("synthetic.missing", missing.toString().trim());
                })
                .choice()
                .when(simple("${exchangeProperty.synthetic.invalid} == true"))
                .log(
                        LoggingLevel.WARN,
                        "Skip synthetic order ${exchangeProperty.ampath.order_uuid} — missing ${exchangeProperty.synthetic.missing}")
                .when(simple("${exchangeProperty.synthetic.type_ok} == false"))
                .log(
                        LoggingLevel.WARN,
                        "Skip synthetic order ${exchangeProperty.ampath.order_uuid} — type ${exchangeProperty.synthetic.order_type_uuid} not configured")
                .when(simple("${exchangeProperty.event.operation} == 'd' "
                        + "|| ${exchangeProperty.synthetic.voided} == 1 "
                        + "|| ${exchangeProperty.synthetic.order_action} == 'DISCONTINUE'"))
                .setHeader("openmrs.fhir.event", constant("d"))
                .process(ex -> ex.getMessage().setBody(buildBundle(ex)))
                .to("direct:service-request-to-sale-order-processor")
                .otherwise()
                .setHeader("openmrs.fhir.event", simple("${exchangeProperty.event.operation}"))
                .process(ex -> ex.getMessage().setBody(buildBundle(ex)))
                .to("direct:service-request-to-sale-order-processor")
                .endChoice()
                .end();
    }

    private static String extractOrderTypeUuid(Map<String, Object> order) {
        Object o = order.getOrDefault("orderType", order.get("order_type"));
        if (o instanceof Map) {
            Object u = ((Map<?, ?>) o).get("uuid");
            return u instanceof String ? (String) u : null;
        }
        return o instanceof String ? (String) o : null;
    }

    private static int parseVoided(Map<String, Object> order) {
        Object v = order.getOrDefault("voided", order.get("isVoided"));
        if (v instanceof Boolean) {
            return (Boolean) v ? 1 : 0;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() == 1 ? 1 : 0;
        }
        if (v instanceof String) {
            String s = (String) v;
            return "1".equals(s) || "true".equalsIgnoreCase(s) ? 1 : 0;
        }
        return 0;
    }

    private static String parseOrderAction(Map<String, Object> order) {
        Object a = order.get("orderAction");
        if (a == null) {
            a = order.get("order_action");
        }
        if (a == null) {
            a = order.get("action");
        }
        return a instanceof String ? (String) a : null;
    }

    @SuppressWarnings("unchecked")
    private static void parseConceptPatientEncounter(Map<String, Object> order, Exchange exchange) {
        Object c = order.get("concept");
        if (c == null) {
            c = order.get("conceptReference");
        }
        String conceptUuid = null;
        String conceptDisplay = null;
        if (c instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) c;
            if (m.get("uuid") instanceof String) {
                conceptUuid = (String) m.get("uuid");
            }
            if (m.get("display") instanceof String) {
                conceptDisplay = (String) m.get("display");
            } else if (m.get("name") instanceof String) {
                conceptDisplay = (String) m.get("name");
            }
        } else if (c instanceof String) {
            conceptUuid = (String) c;
        }
        exchange.setProperty("synthetic.concept_uuid", conceptUuid);
        exchange.setProperty("synthetic.concept_display", conceptDisplay);

        Object p = order.get("patient");
        if (p instanceof Map && ((Map<?, ?>) p).get("uuid") instanceof String) {
            exchange.setProperty("synthetic.patient_uuid", ((Map<?, ?>) p).get("uuid"));
        }
        Object e = order.get("encounter");
        if (e instanceof Map && ((Map<?, ?>) e).get("uuid") instanceof String) {
            exchange.setProperty("synthetic.encounter_uuid", ((Map<?, ?>) e).get("uuid"));
        }
        Object o = order.get("orderer");
        if (o instanceof Map) {
            Map<String, Object> om = (Map<String, Object>) o;
            if (om.get("uuid") instanceof String) {
                exchange.setProperty("synthetic.orderer_uuid", om.get("uuid"));
            }
            if (om.get("display") instanceof String) {
                exchange.setProperty("synthetic.orderer_display", om.get("display"));
            }
        }
    }

    private static boolean parseCompleted(Map<String, Object> order) {
        Object ds = order.get("dateStopped");
        if (ds == null) {
            ds = order.get("date_stopped");
        }
        if (ds != null) {
            if (ds instanceof String) {
                return StringUtils.hasText((String) ds);
            }
            return true;
        }
        Object st = order.get("status");
        if (st == null) {
            st = order.get("orderStatus");
        }
        return st instanceof String && "COMPLETED".equalsIgnoreCase((String) st);
    }

    private static Bundle buildBundle(Exchange exchange) {
        String id = exchange.getProperty("ampath.order_uuid", String.class);
        boolean completed = Boolean.TRUE.equals(exchange.getProperty("synthetic.completed", Boolean.class));
        String conceptUuid = exchange.getProperty("synthetic.concept_uuid", String.class);
        String conceptDisplay = exchange.getProperty("synthetic.concept_display", String.class);
        String patientUuid = exchange.getProperty("synthetic.patient_uuid", String.class);
        String encounterUuid = exchange.getProperty("synthetic.encounter_uuid", String.class);
        String ordererUuid = exchange.getProperty("synthetic.orderer_uuid", String.class);
        String ordererDisplay = exchange.getProperty("synthetic.orderer_display", String.class);

        ServiceRequest sr = new ServiceRequest();
        sr.setId(id);
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setStatus(
                completed ? ServiceRequest.ServiceRequestStatus.COMPLETED : ServiceRequest.ServiceRequestStatus.ACTIVE);
        CodeableConcept code = new CodeableConcept();
        code.setText(conceptDisplay != null ? conceptDisplay : conceptUuid);
        code.addCoding(new Coding().setCode(conceptUuid));
        sr.setCode(code);
        sr.setSubject(new Reference("Patient/" + patientUuid));
        sr.setEncounter(new Reference("Encounter/" + encounterUuid));
        Reference req = new Reference("Practitioner/" + (ordererUuid != null ? ordererUuid : ""));
        req.setDisplay(ordererDisplay != null ? ordererDisplay : "");
        sr.setRequester(req);
        Bundle b = new Bundle();
        b.addEntry().setResource(sr);
        return b;
    }
}
