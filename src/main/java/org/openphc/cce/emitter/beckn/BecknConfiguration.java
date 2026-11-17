package org.openphc.cce.emitter.beckn;

import org.openphc.cce.emitter.config.EmitterProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Beans of Beckn mode ({@code emitter.target=beckn}). Checks the settings at start and fails fast
 * with the names of the missing ones.
 */
@Configuration
@ConditionalOnProperty(prefix = "emitter", name = "target", havingValue = "beckn", matchIfMissing = true)
public class BecknConfiguration {

    public static final String ONIX_REST_TEMPLATE = "onixRestTemplate";
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public BecknConfiguration(EmitterProperties properties) {
        BecknSettingsValidator.validate(properties.getBeckn());
    }

    /**
     * The client for the onix callers: waits up to {@code emitter.beckn.onix.timeout} for an answer,
     * and never throws for an HTTP status, so {@link OnixClient} can read every Ack / Nack body.
     */
    @Bean(name = ONIX_REST_TEMPLATE)
    @Qualifier(ONIX_REST_TEMPLATE)
    public RestTemplate onixRestTemplate(EmitterProperties properties) {
        Duration timeout = properties.getBeckn().getOnix().getTimeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration connect = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }
}
