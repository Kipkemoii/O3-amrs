/*
 * Copyright © 2021, Ozone HIS <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.eip.odoo.openmrs.mapper.odoo;

import com.ozonehis.eip.odoo.openmrs.client.OdooUtils;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.CountryHandler;
import com.ozonehis.eip.odoo.openmrs.handlers.odoo.CountryStateHandler;
import com.ozonehis.eip.odoo.openmrs.mapper.ToOdooMapping;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Setter;
import org.hl7.fhir.r4.model.Element;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Setter
@Component
public class PartnerMapper implements ToOdooMapping<Patient, Partner> {

    private static final String ADDRESS_EXTENSION_URL = "http://fhir.openmrs.org/ext/address";

    private static final String ADDRESS1_EXTENSION = "http://fhir.openmrs.org/ext/address#address1";

    private static final String ADDRESS2_EXTENSION = "http://fhir.openmrs.org/ext/address#address2";

    @Autowired
    private CountryHandler countryHandler;

    @Autowired
    private CountryStateHandler countryStateHandler;

    @Value("${eip.odoo.partner.append-identifier-to-name:true}")
    private boolean appendIdentifierToPartnerName;

    @Override
    public Partner toOdoo(Patient patient) {
        if (patient == null) {
            return null;
        }
        Partner partner = new Partner();
        partner.setPartnerRef(patient.getIdPart());
        partner.setPartnerActive(patient.getActive());
        String patientName = getPatientName(patient).orElse("");
        String patientIdentifier = getIdentifierForPartnerDisplay(patient).orElse("");
        partner.setPartnerComment(patientIdentifier);
        partner.setPartnerExternalId(patientIdentifier);
        partner.setPartnerName(
                appendIdentifierToPartnerName
                        ? formatPartnerDisplayName(patientName, patientIdentifier)
                        : patientName);
        partner.setPartnerBirthDate(
                OdooUtils.convertEEEMMMddDateToOdooFormat(patient.getBirthDate().toString()));

        addAddress(patient, partner);
        return partner;
    }

    /**
     * Patient identifier shown on the Odoo partner and appended to the display name: FHIR {@code USUAL}
     * (preferred) first, then {@code OFFICIAL}, then any first non-blank value.
     */
    protected Optional<String> getIdentifierForPartnerDisplay(Patient patient) {
        if (patient == null || !patient.hasIdentifier()) {
            return Optional.empty();
        }
        return firstIdentifierWithUse(patient, Identifier.IdentifierUse.USUAL)
                .or(() -> firstIdentifierWithUse(patient, Identifier.IdentifierUse.OFFICIAL))
                .or(() -> patient.getIdentifier().stream()
                        .filter(Identifier::hasValue)
                        .map(Identifier::getValue)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .findFirst());
    }

    private static Optional<String> firstIdentifierWithUse(Patient patient, Identifier.IdentifierUse use) {
        return patient.getIdentifier().stream()
                .filter(i -> i.hasValue() && use.equals(i.getUse()))
                .map(Identifier::getValue)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst();
    }

    static String formatPartnerDisplayName(String patientName, String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return patientName != null ? patientName : "";
        }
        String id = identifier.trim();
        if (!StringUtils.hasText(patientName)) {
            return "(" + id + ")";
        }
        return patientName.trim() + " (" + id + ")";
    }

    protected Optional<String> getPatientName(Patient patient) {
        return patient.getName().stream()
                .findFirst()
                .map(name -> name.getGiven().get(0) + " " + name.getFamily());
    }

    protected void addAddress(Patient patient, Partner partner) {
        if (patient.hasAddress()) {
            patient.getAddress().forEach(fhirAddress -> {
                partner.setPartnerCity(fhirAddress.getCity());
                partner.setPartnerCountryId(countryHandler.getCountryId(fhirAddress.getCountry()));
                partner.setPartnerZip(fhirAddress.getPostalCode());
                partner.setPartnerStateId(countryStateHandler.getStateId(fhirAddress.getState()));
                if (fhirAddress.getType() != null) {
                    partner.setPartnerType(fhirAddress.getType().getDisplay());
                }

                if (fhirAddress.hasExtension()) {
                    List<Extension> extensions = fhirAddress.getExtension();
                    List<Extension> addressExtensions = extensions.stream()
                            .filter(extension -> extension.getUrl().equals(ADDRESS_EXTENSION_URL))
                            .findFirst()
                            .map(Element::getExtension)
                            .orElse(new ArrayList<>());

                    addressExtensions.stream()
                            .filter(extension -> extension.getUrl().equals(ADDRESS1_EXTENSION))
                            .findFirst()
                            .ifPresent(extension -> partner.setPartnerStreet(
                                    extension.getValue().toString()));

                    addressExtensions.stream()
                            .filter(extension -> extension.getUrl().equals(ADDRESS2_EXTENSION))
                            .findFirst()
                            .ifPresent(extension -> partner.setPartnerStreet2(
                                    extension.getValue().toString()));
                }
            });
        }
    }
}
