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
 * Beckn mode only: checks that the Sygnal caller of this emitter's onix ({@code onix-openmrs}) is
 * reachable, by a GET to {@code {caller-url}/sygnal/event/report}. Any HTTP answer counts as
 * reachable (the caller takes only POST); no answer means DOWN. Nothing is checked when the
 * {@code cce-events} leg is off. Replaces the OpenHIM check, which is not on the Beckn path.
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
            targets.put("caller", strip(beckn.getOnix().getCallerUrl()) + "/" + BecknMessageBuilder.ACTION_EVENT_REPORT);
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
