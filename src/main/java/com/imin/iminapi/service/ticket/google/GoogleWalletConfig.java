package com.imin.iminapi.service.ticket.google;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The one {@link RestClient} that talks to Google Wallet, with its timeouts in
 * a single reviewable place.
 *
 * <p><b>The timeouts are not decorative.</b> Class and object provisioning run
 * <em>inline on the buyer's request thread</em> — the save-link endpoint cannot
 * return a JWT until the object it names exists. A hung connection to
 * {@code walletobjects.googleapis.com} with no timeout would pin a Tomcat worker
 * for as long as the OS lets it, and there is no global HTTP timeout default in
 * this project to fall back on: every client here either sets its own or has
 * none.
 *
 * <p>Five seconds, twice (connect and read), across at most three calls in the
 * worst path — token, class, object — so a total Google outage costs a buyer
 * about fifteen seconds and then a {@code 503} on one button. Everything else
 * about the ticket is already rendered by then.
 *
 * <p>The client is a bean rather than something {@link GoogleWalletApiClient}
 * builds, so a test can bind a {@code MockRestServiceServer} to the builder
 * without a constructor overwriting the mock's request factory — the trap
 * {@code ExpoPushSenderTest} documents.
 */
@Configuration
public class GoogleWalletConfig {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Named, because this project has several {@link RestClient} beans. */
    @Bean
    public RestClient googleWalletRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT)))
                .build();
    }
}
