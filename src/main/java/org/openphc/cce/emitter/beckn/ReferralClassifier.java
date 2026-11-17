package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralDirectionConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralResponseConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides whether a {@code ServiceRequest} also goes to the HMIS peer (docs/beckn-integration.md
 * § 7). It reads only the markers {@code OrderIdentifierEnricher} added, with the systems and codes
 * from {@code emitter.referral.*}. The first matching rule wins:
 *
 * <ol>
 *   <li>{@code inbound} and {@code referral-response}, status {@code completed} / {@code revoked}
 *       → {@code on_status} with {@code COMPLETED} / {@code DECLINED} (referrals in);</li>
 *   <li>{@code outbound} (any status) with a performer facility code the peer serves
 *       → {@code update} (referrals out);</li>
 *   <li>anything else → nothing for the peer.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class ReferralClassifier {

    public enum Kind {
        /** Not a referral the peer needs to hear about. */
        NONE,
        /** Outcome of a referral from the peer: {@code on_status}. */
        OUTCOME_TO_PEER,
        /** Referral out to a facility the peer serves: {@code update}. */
        REFERRAL_OUT_TO_PEER,
        /** Referral out to a facility the peer does not serve (or with no facility code). */
        REFERRAL_OUT_NOT_SERVED
    }

    /** {@code performanceStatus} is set only for {@link Kind#OUTCOME_TO_PEER}. */
    public record Decision(Kind kind, String performanceStatus) {
        static final Decision NONE = new Decision(Kind.NONE, null);
    }

    private final EmitterProperties properties;

    public ReferralClassifier(EmitterProperties properties) {
        this.properties = properties;
    }

    public Decision classify(JsonNode resource) {
        if (resource == null || !"ServiceRequest".equals(BecknJson.text(resource, "resourceType"))) {
            return Decision.NONE;
        }
        ReferralDirectionConfig direction = properties.getReferral().getDirection();
        ReferralResponseConfig response = properties.getReferral().getResponse();
        String status = BecknJson.text(resource, "status");

        if (hasCategory(resource, direction.getCategorySystem(), direction.getInboundCode())
                && hasCategory(resource, response.getCategorySystem(), response.getCategoryCode())) {
            if ("completed".equals(status)) return new Decision(Kind.OUTCOME_TO_PEER, "COMPLETED");
            if ("revoked".equals(status)) return new Decision(Kind.OUTCOME_TO_PEER, "DECLINED");
        }
        if (hasCategory(resource, direction.getCategorySystem(), direction.getOutboundCode())) {
            return servedByPeer(resource, direction.getDestinationIdentifierSystem())
                    ? new Decision(Kind.REFERRAL_OUT_TO_PEER, null)
                    : new Decision(Kind.REFERRAL_OUT_NOT_SERVED, null);
        }
        return Decision.NONE;
    }

    private boolean servedByPeer(JsonNode resource, String facilityCodeSystem) {
        List<String> served = properties.getBeckn().getSpice().getFacilityCodes();
        if (served == null || served.isEmpty() || facilityCodeSystem == null) return false;
        for (JsonNode performer : resource.path("performer")) {
            JsonNode identifier = performer.path("identifier");
            if (facilityCodeSystem.equals(BecknJson.text(identifier, "system"))) {
                String code = BecknJson.text(identifier, "value");
                if (code != null && served.contains(code)) return true;
            }
        }
        return false;
    }

    private static boolean hasCategory(JsonNode resource, String system, String code) {
        if (system == null || code == null) return false;
        for (JsonNode category : resource.path("category")) {
            for (JsonNode coding : category.path("coding")) {
                if (system.equals(BecknJson.text(coding, "system")) && code.equals(BecknJson.text(coding, "code"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
