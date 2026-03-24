package org.openmrs.eip.odoo.ampath.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * Standalone route that consumes {@code direct:fhir-servicerequest} and processes
 * radiology/procedure order events from the OpenMRS Debezium stream.
 *
 * <h3>Why this exists</h3>
 * The OpenMRS FHIR 2 module does not map radiology/procedure order types to
 * {@code ServiceRequest} resources.  Instead of attempting FHIR reads (which always
 * return 404), this route fetches order data directly via the OpenMRS REST Orders API,
 * builds a synthetic FHIR {@link Bundle}, and sends it to the existing
 * {@code ServiceRequestProcessor} pipeline for Odoo sale order creation.
 *
 * <h3>Flow</h3>
 * <pre>
 * Debezium watcher (orders table)
 *   → direct:fhir-servicerequest (body = ServiceRequest with order UUID as ID)
 *   → Extract order UUID from ServiceRequest ID
 *   → REST: fetch order via OpenMRS REST API
 *   → Filter: order_type UUID in EIP_GENERIC_ORDER_TYPE_UUIDS
 *   → Build synthetic ServiceRequest bundle
 *   → direct:service-request-to-sale-order-processor → ServiceRequestProcessor (Odoo)
 * </pre>
 *
 * <h3>Configuration</h3>
 * <pre>
 * EIP_GENERIC_ORDER_TYPE_UUIDS=uuid1,uuid2,...
 * </pre>
 */
@Component
public class GenericOrderRouting extends RouteBuilder {

    @Autowired
    private AmpathGenericOrderProperties genericOrderProperties;

    /**
     * Base URL of the OpenMRS FHIR R4 server (same as EIP_FHIR_SERVER_URL).
     */
    @Value("${eip.fhir.serverUrl}")
    private String fhirServerUrl;

    @Override
    public void configure() {
        if (!genericOrderProperties.isGenericOrderHandlingEnabled()) {
            log.info("EIP_GENERIC_ORDER_TYPE_UUIDS / eip.generic.order.type.uuids is not set — GenericOrderRouting is disabled.");
            return;
        }

        final Set<String> uuidSet = genericOrderProperties.getGenericOrderTypeUuids();

        log.info("GenericOrderRouting enabled for order-type UUIDs: {}", uuidSet);

        // Compute OpenMRS REST base URL from the OpenMRS FHIR base URL.
        // Example: http://backend:8080/openmrs/ws/fhir2/R4 -> http://backend:8080/openmrs
        final String openmrsRestBaseUrl = fhirServerUrl.replaceAll("/ws/fhir2/[^/]+$", "");
        final String openmrsOrderEndpoint = openmrsRestBaseUrl + "/ws/rest/v1/order";

        final ObjectMapper objectMapper = new ObjectMapper();
        final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};

        // Hijack traffic bound for the stock ServiceRequest route.
        interceptSendToEndpoint("direct:fhir-handler-servicerequest")
                .skipSendToOriginalEndpoint()
                .log(LoggingLevel.INFO, ">>> HIJACKED traffic destined for direct:fhir-handler-servicerequest. Diverting to AMPATH client!")
                .to("direct:ampath-generic-orders");

        from("direct:ampath-generic-orders")
                .routeId("ampath-generic-order-router")
                .log(LoggingLevel.INFO, ">>> GenericOrderRouting REACHED! Event received. Body = ${body}")
                .filter(body().isNotNull())

                // Extract the order UUID from the Debezium Event object via Camel OGNL
                .setProperty("ampath.order_uuid", simple("${body.identifier}"))
                .choice()
                    .when(exchangeProperty("ampath.order_uuid").isNull())
                        // Fallback if the body wasn't the event but it's in the property
                        .setProperty("ampath.order_uuid", simple("${exchangeProperty.event.identifier}"))
                .end()
                
                .log(LoggingLevel.INFO, "Extracted ampath.order_uuid = ${exchangeProperty.ampath.order_uuid} from incoming event")
                .filter(exchangeProperty("ampath.order_uuid").isNotNull())

                .log(LoggingLevel.INFO,
                        "GenericOrderRouting: fetching order ${exchangeProperty.ampath.order_uuid} via REST")

                // Fetch the order via OpenMRS REST API
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setBody(constant(""))
                .toD(openmrsOrderEndpoint + "/${exchangeProperty.ampath.order_uuid}")

                // Parse and validate the order JSON
                .process(exchange -> {
                    String body = exchange.getMessage().getBody(String.class);
                    if (!StringUtils.hasText(body)) {
                        throw new IllegalArgumentException(
                                "Empty OpenMRS order REST response for uuid " + exchange.getProperty("ampath.order_uuid"));
                    }

                    Map<String, Object> order = objectMapper.readValue(body, mapType);

                    // ── orderType.uuid ──
                    Object orderTypeObj = order.getOrDefault("orderType", order.get("order_type"));
                    String orderTypeUuid = null;
                    if (orderTypeObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> otMap = (Map<String, Object>) orderTypeObj;
                        Object uuid = otMap.get("uuid");
                        if (uuid instanceof String) {
                            orderTypeUuid = (String) uuid;
                        }
                    } else if (orderTypeObj instanceof String) {
                        orderTypeUuid = (String) orderTypeObj;
                    }

                    exchange.setProperty("generic.order_type_uuid", orderTypeUuid);
                    exchange.setProperty("generic.order_type_match",
                            orderTypeUuid != null && uuidSet.contains(orderTypeUuid));

                    // ── voided ──
                    Object voidedObj = order.getOrDefault("voided", order.get("isVoided"));
                    boolean voided = false;
                    if (voidedObj instanceof Boolean) {
                        voided = (Boolean) voidedObj;
                    } else if (voidedObj instanceof Number) {
                        voided = ((Number) voidedObj).intValue() == 1;
                    } else if (voidedObj instanceof String) {
                        String s = (String) voidedObj;
                        voided = "1".equals(s) || "true".equalsIgnoreCase(s);
                    }
                    exchange.setProperty("generic.voided", voided ? 1 : 0);

                    // ── action ──
                    Object actionObj = order.get("orderAction");
                    if (actionObj == null) actionObj = order.get("order_action");
                    if (actionObj == null) actionObj = order.get("action");
                    String orderAction = (actionObj instanceof String) ? (String) actionObj : null;
                    exchange.setProperty("generic.order_action", orderAction);

                    // ── concept (drives Product lookup) ──
                    Object conceptObj = order.get("concept");
                    if (conceptObj == null) {
                        conceptObj = order.get("conceptReference");
                    }
                    String conceptUuid = null;
                    String conceptDisplay = null;
                    if (conceptObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> conceptMap = (Map<String, Object>) conceptObj;
                        Object uuid = conceptMap.get("uuid");
                        if (uuid instanceof String) {
                            conceptUuid = (String) uuid;
                        }
                        Object display = conceptMap.get("display");
                        if (display instanceof String) {
                            conceptDisplay = (String) display;
                        } else if (conceptMap.get("name") instanceof String) {
                            conceptDisplay = (String) conceptMap.get("name");
                        }
                    } else if (conceptObj instanceof String) {
                        conceptUuid = (String) conceptObj;
                    }

                    // ── patient / encounter / orderer ──
                    Object patientObj = order.get("patient");
                    Object encounterObj = order.get("encounter");
                    Object ordererObj = order.get("orderer");

                    String patientUuid = null;
                    String encounterUuid = null;
                    String ordererUuid = null;
                    String ordererDisplay = null;

                    if (patientObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patientMap = (Map<String, Object>) patientObj;
                        if (patientMap.get("uuid") instanceof String) {
                            patientUuid = (String) patientMap.get("uuid");
                        }
                    }
                    if (encounterObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> encounterMap = (Map<String, Object>) encounterObj;
                        if (encounterMap.get("uuid") instanceof String) {
                            encounterUuid = (String) encounterMap.get("uuid");
                        }
                    }
                    if (ordererObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ordererMap = (Map<String, Object>) ordererObj;
                        if (ordererMap.get("uuid") instanceof String) {
                            ordererUuid = (String) ordererMap.get("uuid");
                        }
                        if (ordererMap.get("display") instanceof String) {
                            ordererDisplay = (String) ordererMap.get("display");
                        }
                    }

                    // ── completed detection ──
                    boolean completed = false;
                    Object dateStoppedObj = order.get("dateStopped");
                    if (dateStoppedObj == null) {
                        dateStoppedObj = order.get("date_stopped");
                    }
                    if (dateStoppedObj != null) {
                        if (dateStoppedObj instanceof String) {
                            completed = StringUtils.hasText((String) dateStoppedObj);
                        } else {
                            completed = true;
                        }
                    } else {
                        Object statusObj = order.get("status");
                        if (statusObj == null) {
                            statusObj = order.get("orderStatus");
                        }
                        if (statusObj instanceof String) {
                            completed = "COMPLETED".equalsIgnoreCase((String) statusObj);
                        }
                    }

                    // ── validate required fields ──
                    StringBuilder missingFields = new StringBuilder();
                    if (conceptUuid == null) missingFields.append("concept_uuid ");
                    if (patientUuid == null) missingFields.append("patient_uuid ");
                    if (encounterUuid == null) missingFields.append("encounter_uuid ");

                    boolean invalid = missingFields.length() > 0;
                    exchange.setProperty("generic.invalid", invalid);
                    exchange.setProperty("generic.missing_fields", missingFields.toString().trim());
                    exchange.setProperty("generic.concept_uuid", conceptUuid);
                    exchange.setProperty("generic.concept_display", conceptDisplay);
                    exchange.setProperty("generic.patient_uuid", patientUuid);
                    exchange.setProperty("generic.encounter_uuid", encounterUuid);
                    exchange.setProperty("generic.orderer_uuid", ordererUuid);
                    exchange.setProperty("generic.orderer_display", ordererDisplay);
                    exchange.setProperty("generic.completed", completed);
                })

                .log(LoggingLevel.INFO,
                        "GenericOrderRouting: order_type_uuid=${exchangeProperty.generic.order_type_uuid} match=${exchangeProperty.generic.order_type_match}")

                .choice()
                    // Skip order when required fields are missing.
                    .when(simple("${exchangeProperty.generic.invalid} == true"))
                        .log(LoggingLevel.WARN, "GenericOrderRouting: skipping order ${exchangeProperty.ampath.order_uuid} due to missing fields: ${exchangeProperty.generic.missing_fields}")

                    // Delete / void
                    .when(simple("${exchangeProperty.event.operation} == 'd' "
                            + "|| ${exchangeProperty.generic.voided} == 1 "
                            + "|| ${exchangeProperty.generic.order_action} == 'DISCONTINUE'"))
                        .log(LoggingLevel.INFO, "GenericOrderRouting: processing delete/void order ${exchangeProperty.ampath.order_uuid}")
                        .setHeader("openmrs.fhir.event", constant("d"))
                        .process(exchange -> exchange.getMessage().setBody(buildBundle(exchange)))
                        .to("direct:service-request-to-sale-order-processor")

                    // Create / update
                    .otherwise()
                        .log(LoggingLevel.INFO, "GenericOrderRouting: processing create/update order ${exchangeProperty.ampath.order_uuid}")
                        .setHeader("openmrs.fhir.event",
                                simple("${exchangeProperty.event.operation}"))
                        .process(exchange -> exchange.getMessage().setBody(buildBundle(exchange)))
                        .to("direct:service-request-to-sale-order-processor")
                .endChoice()

                .end();
    }

    /**
     * Build a synthetic FHIR {@link Bundle} from exchange properties.
     * The bundle contains a single {@link ServiceRequest} entry with references
     * to Patient and Encounter so that {@code ServiceRequestProcessor} can
     * look them up and create/update the Odoo sale order.
     */
    private Bundle buildBundle(Exchange exchange) {
        String serviceRequestId = exchange.getProperty("ampath.order_uuid", String.class);
        boolean completed = Boolean.TRUE.equals(exchange.getProperty("generic.completed", Boolean.class));

        String conceptUuid = exchange.getProperty("generic.concept_uuid", String.class);
        String conceptDisplay = exchange.getProperty("generic.concept_display", String.class);
        String patientUuid = exchange.getProperty("generic.patient_uuid", String.class);
        String encounterUuid = exchange.getProperty("generic.encounter_uuid", String.class);
        String ordererUuid = exchange.getProperty("generic.orderer_uuid", String.class);
        String ordererDisplay = exchange.getProperty("generic.orderer_display", String.class);

        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setId(serviceRequestId);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        serviceRequest.setStatus(
                completed ? ServiceRequest.ServiceRequestStatus.COMPLETED : ServiceRequest.ServiceRequestStatus.ACTIVE);

        CodeableConcept code = new CodeableConcept();
        code.setText(conceptDisplay != null ? conceptDisplay : conceptUuid);
        code.addCoding(new Coding().setCode(conceptUuid));
        serviceRequest.setCode(code);

        serviceRequest.setSubject(new Reference("Patient/" + patientUuid));
        serviceRequest.setEncounter(new Reference("Encounter/" + encounterUuid));

        Reference requester = new Reference("Practitioner/" + (ordererUuid != null ? ordererUuid : ""));
        requester.setDisplay(ordererDisplay != null ? ordererDisplay : "");
        serviceRequest.setRequester(requester);

        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(serviceRequest);
        return bundle;
    }
}
