package org.openmrs.eip.odoo.ampath.mapper;

import com.ozonehis.eip.odoo.openmrs.mapper.odoo.PartnerMapper;
import com.ozonehis.eip.odoo.openmrs.model.Partner;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extends the stock {@link PartnerMapper} to append patient identifiers in
 * brackets after the display name, e.g.
 * {@code "John Doe [AMRS-12345, OpenMRS ID, a1b2c3d4-...]"} instead of just
 * {@code "John Doe"}.
 *
 * <p>Unlike the upstream {@code getPreferredPatientIdentifier}, which only keeps
 * identifiers with {@code use == official}, this includes <strong>every</strong>
 * non-blank {@link Identifier#getValue()} on the Patient so partners stay
 * distinguishable when no official identifier is present.</p>
 *
 * <p>The Patient logical id ({@link Patient#getIdPart()}) is appended when it
 * is not already one of the identifier values (typical OpenMRS patient UUID).</p>
 *
 * <p>Annotated {@link Primary} so Spring injects this mapper wherever a
 * {@code PartnerMapper} is required, replacing the upstream default.</p>
 */
@Primary
@Component
public class AmpathPartnerMapper extends PartnerMapper {

    @Override
    public Partner toOdoo(Patient patient) {
        Partner partner = super.toOdoo(patient);
        if (partner == null) {
            return null;
        }

        String suffix = buildIdentifierSuffix(patient);
        if (!StringUtils.hasText(suffix)) {
            return partner;
        }

        String currentName = partner.getPartnerName();
        if (currentName != null && !currentName.contains("[")) {
            partner.setPartnerName(currentName + " [" + suffix + "]");
        }

        return partner;
    }

    /**
     * Distinct non-blank {@link Identifier} values (FHIR order), then logical id if not duplicate.
     */
    static String buildIdentifierSuffix(Patient patient) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();

        if (patient.hasIdentifier()) {
            for (Identifier id : patient.getIdentifier()) {
                if (!id.hasValue()) {
                    continue;
                }
                String value = id.getValue().trim();
                if (!StringUtils.hasText(value) || seen.contains(value)) {
                    continue;
                }
                seen.add(value);
                parts.add(formatIdentifierPart(id, value));
            }
        }

        String logicalId = patient.getIdPart();
        if (StringUtils.hasText(logicalId)) {
            logicalId = logicalId.trim();
            if (!seen.contains(logicalId)) {
                parts.add(logicalId);
            }
        }

        return parts.stream().collect(Collectors.joining(", "));
    }

    /**
     * Prefer {@code type.text} or first coding display/text plus value when it adds context;
     * otherwise just the value.
     */
    private static String formatIdentifierPart(Identifier id, String value) {
        String label = null;
        if (id.hasType()) {
            if (id.getType().hasText()) {
                label = id.getType().getText();
            } else if (id.getType().hasCoding()) {
                Coding c = id.getType().getCodingFirstRep();
                if (c.hasDisplay()) {
                    label = c.getDisplay();
                } else if (c.hasCode()) {
                    label = c.getCode();
                }
            }
        }
        if (StringUtils.hasText(label) && !value.equalsIgnoreCase(label)) {
            return label + ": " + value;
        }
        return value;
    }
}
