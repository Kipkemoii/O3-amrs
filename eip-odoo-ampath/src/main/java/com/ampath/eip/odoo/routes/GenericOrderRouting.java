package com.ampath.eip.odoo.routes;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routes generic/procedure order events from the OpenMRS Debezium stream into
 * the existing Odoo ServiceRequest processing pipeline.
 *
 * <h3>Why this exists</h3>
 * The upstream {@code ServiceRequestRouter} only recognises two hardcoded order-type
 * UUIDs (lab test + imaging).  Any other order type is silently filtered out, so
 * procedures, clinical notes, and other "generic" orders never reach Odoo.
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
 * SQL: look up order_type UUID for the changed order
 *   ↓
 * Filter: order_type UUID in EIP_GENERIC_ORDER_TYPE_UUIDS
 *   ↓
 * Fetch ServiceRequest from FHIR (404 → skip gracefully)
 *   ↓
 * direct:fhir-servicerequest  →  ServiceRequestRouting (Odoo)
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

        // ── Main route ────────────────────────────────────────────────────────────
        from("direct:ampath-generic-order-listener")
                .routeId("ampath-generic-order-router")

                // Only process `orders` table events
                .filter(simple("${exchangeProperty.event.tableName} == 'orders'"))

                // Look up the order_type UUID for this order from the OpenMRS DB
                .toD("sql:SELECT ot.uuid AS order_type_uuid "
                        + "FROM order_type ot "
                        + "JOIN orders o ON o.order_type_id = ot.order_type_id "
                        + "WHERE o.uuid = '${exchangeProperty.event.identifier}'"
                        + "?dataSource=#openmrsDataSource")

                // Skip if the order_type UUID is not in our configured set
                .filter(exchange -> {
                    var rows = exchange.getMessage().getBody(java.util.List.class);
                    if (rows == null || rows.isEmpty()) return false;
                    @SuppressWarnings("unchecked")
                    var row = (java.util.Map<String, Object>) rows.get(0);
                    String uuid = (String) row.get("order_type_uuid");
                    return uuid != null && uuidSet.contains(uuid);
                })

                .log(LoggingLevel.INFO,
                        "GenericOrderRouting: processing order ${exchangeProperty.event.identifier}")

                // Check voided / operation flag before hitting FHIR
                .toD("sql:SELECT voided, order_action, previous_order_id "
                        + "FROM orders WHERE uuid = '${exchangeProperty.event.identifier}'"
                        + "?dataSource=#openmrsDataSource")

                .choice()
                    // ── Delete / void ────────────────────────────────────────────
                    .when(simple("${exchangeProperty.event.operation} == 'd' "
                            + "|| ${body[0]['voided']} == 1"))
                        .setHeader("openmrs.fhir.event", constant("d"))
                        .setBody(simple("${exchangeProperty.event.identifier}"))
                        .to("direct:fhir-servicerequest")

                    // ── DISCONTINUE ──────────────────────────────────────────────
                    .when(simple("${body[0]['order_action']} == 'DISCONTINUE'"))
                        .setHeader("openmrs.fhir.event", constant("d"))
                        .setBody(simple("${exchangeProperty.event.identifier}"))
                        .to("direct:fhir-servicerequest")

                    // ── Create / update — fetch from FHIR ────────────────────────
                    .otherwise()
                        .setHeader("openmrs.fhir.event",
                                simple("${exchangeProperty.event.operation}"))
                        // Fetch the full FHIR ServiceRequest bundle
                        .toD(fhirServerUrl
                                + "/ServiceRequest/${exchangeProperty.event.identifier}"
                                + "?$include=ServiceRequest:encounter&$include=ServiceRequest:patient"
                                + "&bridgeEndpoint=true&httpMethod=GET")
                        .to("direct:fhir-servicerequest")
                .endChoice()

                .end();
    }
}
