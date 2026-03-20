package org.openmrs.eip.odoo.ampath.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * Routes generic/procedure order events from the OpenMRS Debezium stream into
 * the existing Odoo ServiceRequest processing pipeline.
 *
 * <h3>Why this exists</h3>
 * The upstream {@code ServiceRequestRouter} only recognises two hardcoded order-type
 * UUIDs (lab test + imaging).  Any other order type is silently filtered out, so
 * procedures, clinical notes, and other "generic" orders never reach Odoo.
 *
 * <h3>EIP client classpath scan</h3>
 * This class lives under {@code org.openmrs.eip.*} so the Ozone EIP client picks it up
 * when {@code eip.app.scan.packages} includes {@code org.openmrs.eip} (see
 * <a href="https://github.com/ozone-his/eip-client">ozone-his/eip-client</a>).
 *
 * <h3>Configuration</h3>
 * <pre>
 * # Comma-separated list of OpenMRS order-type UUIDs to treat as generic orders.
 * # Events for these types are forwarded to the ServiceRequest Odoo processor.
 * EIP_GENERIC_ORDER_TYPE_UUIDS=uuid1,uuid2,...
 * </pre>
 *
 * <h3>Event flow</h3>
 * <pre>
 * DB_EVENT_DESTINATIONS_ODOO must include "direct:ampath-generic-order-listener"
 *   ↓
 * Filter: table == 'orders'
 *   ↓
 * REST: fetch order via OpenMRS REST
 *   ↓
 * Filter: order_type UUID in EIP_GENERIC_ORDER_TYPE_UUIDS
 *   ↓
 * Build ServiceRequest bundle locally (no ServiceRequest FHIR reads)
 *   ↓
 * direct:service-request-to-sale-order-processor  → ServiceRequestProcessor (Odoo)
 * </pre>
 */
@Component
public class GenericOrderRouting extends RouteBuilder {

    /**
     * Comma-separated list of OpenMRS order-type UUIDs that should be treated as
     * generic service requests and synced to Odoo.  Leave empty to disable this route.
     */
    @Value("${eip.generic.order.type.uuids:}")
    private String genericOrderTypeUuids;

    /**
     * Base URL of the OpenMRS FHIR R4 server (same as EIP_FHIR_SERVER_URL).
     */
    @Value("${eip.fhir.serverUrl}")
    private String fhirServerUrl;

    @Override
    public void configure() {
        if (!StringUtils.hasText(genericOrderTypeUuids)) {
            log.info("EIP_GENERIC_ORDER_TYPE_UUIDS is not set — GenericOrderRouting is disabled.");
            return;
        }

        Set<String> uuidSet = Arrays.stream(genericOrderTypeUuids.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        if (uuidSet.isEmpty()) {
            log.info("EIP_GENERIC_ORDER_TYPE_UUIDS parsed to empty set — GenericOrderRouting is disabled.");
            return;
        }

        log.info("GenericOrderRouting enabled for order-type UUIDs: {}", uuidSet);

        // ── Handle 404 from FHIR gracefully ──────────────────────────────────────
        // If the ServiceRequest has been deleted/voided on the FHIR side before this
        // route reads it, treat it as a delete event rather than crashing.
        onException(Exception.class)
                .onWhen(exceptionMessage().contains("404"))
                .handled(true)
                .log(LoggingLevel.WARN,
                        "GenericOrderRouting: ServiceRequest ${exchangeProperty.event.identifier} not found in FHIR (404) — skipping.");

        // Compute OpenMRS REST base URL from the OpenMRS FHIR base URL.
        // Example: http://backend:8080/openmrs/ws/fhir2/R4 -> http://backend:8080/openmrs
        final String openmrsRestBaseUrl = fhirServerUrl.replaceAll("/ws/fhir2/[^/]+$", "");
        final String openmrsOrderEndpoint = openmrsRestBaseUrl + "/ws/rest/v1/order";

        final ObjectMapper objectMapper = new ObjectMapper();
        final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};

        // ── Main route ────────────────────────────────────────────────────────────
        from("direct:ampath-generic-order-listener")
                .routeId("ampath-generic-order-router")

                // Only process `orders` table events
                .filter(simple("${exchangeProperty.event.tableName} == 'orders'"))

                // Fetch the OpenMRS order via REST (no SQL)
                .toD(openmrsOrderEndpoint + "/${exchangeProperty.event.identifier}")
                .process(exchange -> {
                    String body = exchange.getMessage().getBody(String.class);
                    if (!StringUtils.hasText(body)) {
                        throw new IllegalArgumentException(
                                "Empty OpenMRS order REST response for uuid " + exchange.getProperty("event.identifier"));
                    }

                    Map<String, Object> order = objectMapper.readValue(body, mapType);

                    // orderType.uuid (or similarly named fields depending on OpenMRS version)
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

                    // voided: boolean, or 0/1
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

                    // Action string (DISCONTINUE)
                    Object actionObj = order.get("orderAction");
                    if (actionObj == null) actionObj = order.get("order_action");
                    if (actionObj == null) actionObj = order.get("action");
                    String orderAction = (actionObj instanceof String) ? (String) actionObj : null;
                    exchange.setProperty("generic.order_action", orderAction);

                    // Concept (drives Product lookup)
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

                    // Patient / Encounter (drives OpenMRS FHIR reads inside ServiceRequestProcessor)
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

                    // Completed detection (drives create/update vs delete in ServiceRequestProcessor)
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

                // Skip if the order_type UUID is not in our configured set
                .filter(exchange -> {
                    String uuid = exchange.getProperty("generic.order_type_uuid", String.class);
                    return uuid != null && uuidSet.contains(uuid);
                })

                // Skip if we couldn't map required fields to a ServiceRequest
                .filter(exchange -> {
                    boolean invalid = Boolean.TRUE.equals(exchange.getProperty("generic.invalid", Boolean.class));
                    if (invalid) {
                        String missingFields = exchange.getProperty("generic.missing_fields", String.class);
                        log.warn("GenericOrderRouting: skipping order {} due to missing fields: {}",
                                exchange.getProperty("event.identifier"), missingFields);
                    }
                    return !invalid;
                })

                .log(LoggingLevel.INFO,
                        "GenericOrderRouting: processing order ${exchangeProperty.event.identifier}")

                .choice()
                    // ── Delete / void ────────────────────────────────────────────
                    .when(simple("${exchangeProperty.event.operation} == 'd' "
                            + "|| ${exchangeProperty.generic.voided} == 1 "
                            + "|| ${exchangeProperty.generic.order_action} == 'DISCONTINUE'"))
                        .setHeader("openmrs.fhir.event", constant("d"))
                        .process(exchange -> {
                            String serviceRequestId = exchange.getProperty("event.identifier", String.class);
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
                            exchange.getMessage().setBody(bundle);
                        })
                        .to("direct:service-request-to-sale-order-processor")

                    // ── Create / update — fetch from FHIR ────────────────────────
                    .otherwise()
                        .setHeader("openmrs.fhir.event",
                                simple("${exchangeProperty.event.operation}"))
                        .process(exchange -> {
                            String serviceRequestId = exchange.getProperty("event.identifier", String.class);
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
                            exchange.getMessage().setBody(bundle);
                        })
                        .to("direct:service-request-to-sale-order-processor")
                .endChoice()

                .end();
    }
}
