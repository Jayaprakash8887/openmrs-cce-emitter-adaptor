package org.openphc.cce.emitter.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.service.ForwardResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Entry point of Beckn mode (docs/beckn-integration.md § 4). Called by {@code FhirPollerService}
 * in place of {@code ForwardingEngine} with the enriched resource JSON. Sends one
 * {@code sygnal/event/report} to the HIE, which passes it on to CCE. The result is a success only if
 * the message got {@code Ack}.
 *
 * <p>A resource with no patient (for example {@code Location}, {@code Practitioner}) is skipped:
 * {@code patientId} is required by the {@code CceClinicalEvent} box.
 */
@Service
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknTargetForwarder {

    private static final Logger log = LoggerFactory.getLogger(BecknTargetForwarder.class);
    public static final String METRIC_SKIPPED = "openmrs.beckn.resources.skipped";

    private final EmitterProperties properties;
    private final EventIdFactory eventIdFactory;
    private final PatientIdReader patientIdReader;
    private final BecknMessageBuilder messageBuilder;
    private final OnixClient onixClient;
    private final MeterRegistry meterRegistry;

    public BecknTargetForwarder(EmitterProperties properties,
                                EventIdFactory eventIdFactory,
                                PatientIdReader patientIdReader,
                                BecknMessageBuilder messageBuilder,
                                OnixClient onixClient,
                                MeterRegistry meterRegistry) {
        this.properties = properties;
        this.eventIdFactory = eventIdFactory;
        this.patientIdReader = patientIdReader;
        this.messageBuilder = messageBuilder;
        this.onixClient = onixClient;
        this.meterRegistry = meterRegistry;
    }

    public ForwardResult forward(String resourceJson, String resourceType) {
        JsonNode resource;
        try {
            resource = BecknJson.parse(resourceJson);
        } catch (IllegalArgumentException e) {
            skipped(resourceType, "invalid-json");
            log.error("beckn skip resourceType={} reason=invalid-json", resourceType);
            return ForwardResult.skipped("invalid-json");
        }
        String patientId = patientIdReader.read(resource);
        if (patientId == null) {
            skipped(resourceType, "no-patient");
            log.info("beckn skip resourceType={} reason=no-patient", resourceType);
            return ForwardResult.skipped("no-patient");
        }
        if (!properties.getBeckn().getLegs().isCceEvents()) {
            skipped(resourceType, "all-legs-off");
            return ForwardResult.skipped("no Beckn leg on for this resource");
        }

        String eventId = eventIdFactory.eventId(resource);
        OnixClient.SendResult r = onixClient.send(messageBuilder.eventReport(resource, eventId, patientId));
        if (r.isAck()) {
            return ForwardResult.success(r.httpStatus());
        }
        return r.httpStatus() == 0
                ? ForwardResult.unreachable(r.attempts())
                : ForwardResult.failure(r.httpStatus(), null);
    }

    private void skipped(String resourceType, String reason) {
        meterRegistry.counter(METRIC_SKIPPED, Tags.of("resourceType", String.valueOf(resourceType), "reason", reason))
                .increment();
    }
}
