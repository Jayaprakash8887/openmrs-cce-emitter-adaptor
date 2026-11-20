package org.openphc.cce.emitter.health;

import org.openphc.cce.emitter.beckn.BecknMessageBuilder;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.BecknConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beckn mode only: checks that the onix callers the emitter posts to are reachable: the Sygnal caller
 * of {@code onix-openmrs} ({@code {sygnal.caller-url}/sygnal/event/report}, CCE events leg), and the
 * Beckn callers {@code onix-bap} ({@code /update}, referrals out) and {@code onix-bpp}
 * ({@code /on_status}, referrals in). Any HTTP answer counts as reachable (the callers take only
 * POST); no answer means DOWN. Only the callers of the legs that are on are checked. Replaces the
 * OpenHIM check, which is not on the Beckn path.
 */
@Component("onix")
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class OnixHealthIndicator implements HealthIndicator {

    private static final int PROBE_TIMEOUT_MS = 5000;

    private final EmitterProperties properties;
    private final RestTemplate probe;

    public OnixHealthIndicator(EmitterProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_TIMEOUT_MS);
        factory.setReadTimeout(PROBE_TIMEOUT_MS);
        this.probe = new RestTemplate(factory);
        this.probe.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    @Override
    public Health health() {
        BecknConfig beckn = properties.getBeckn();
        Map<String, String> targets = new LinkedHashMap<>();
        if (beckn.getLegs().isCceEvents()) {
            targets.put("sygnal", strip(beckn.getSygnal().getCallerUrl()) + "/" + BecknMessageBuilder.ACTION_EVENT_REPORT);
        }
        if (beckn.getLegs().isReferralsOut()) {
            targets.put("bap", strip(beckn.getOnix().getBapCallerUrl()) + "/update");
        }
        if (beckn.getLegs().isReferralsIn()) {
            targets.put("bpp", strip(beckn.getOnix().getBppCallerUrl()) + "/on_status");
        }
        Health.Builder builder = Health.up();
        for (Map.Entry<String, String> target : targets.entrySet()) {
            String url = target.getValue();
            long start = System.currentTimeMillis();
            try {
                int code = probe.exchange(url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Void.class)
                        .getStatusCode().value();
                builder.withDetail(target.getKey(), Map.of("url", url, "status", code,
                        "responseTimeMs", System.currentTimeMillis() - start));
            } catch (Exception e) {
                builder.down().withDetail(target.getKey(), Map.of("url", url, "error", String.valueOf(e.getMessage())));
            }
        }
        return builder.build();
    }

    private static String strip(String s) {
        return s == null ? "" : (s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }
}
