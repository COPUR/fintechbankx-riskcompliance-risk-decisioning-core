package com.bank.risk.infrastructure.external;

import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * Wiring of the customer credit client: a {@link RestClient} on the customer
 * service's base URL (CUSTOMER_SERVICE_URL, in-cluster through the mesh) with
 * short timeouts, so an unreachable customer service answers 503 quickly, and
 * the client-credentials token provider on the realm's token endpoint.
 */
@Configuration
public class CustomerClientConfiguration {

    @Bean
    RestClient customerRestClient(RestClient.Builder builder,
                                  @Value("${risk.customer.base-url}") String baseUrl,
                                  @Value("${risk.customer.connect-timeout:PT2S}") Duration connectTimeout,
                                  @Value("${risk.customer.read-timeout:PT3S}") Duration readTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("risk.customer.base-url (CUSTOMER_SERVICE_URL) must name the customer service");
        }
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout).withReadTimeout(readTimeout)))
                .build();
    }

    @Bean
    BearerTokenProvider customerTokenProvider(RestClient.Builder builder,
                                              @Value("${risk.customer.token-uri}") String tokenUri,
                                              @Value("${risk.customer.client-id}") String clientId,
                                              @Value("${risk.customer.client-credential:}") String clientCredential,
                                              @Value("${risk.customer.connect-timeout:PT2S}") Duration connectTimeout,
                                              @Value("${risk.customer.read-timeout:PT3S}") Duration readTimeout) {
        RestClient tokenClient = builder.clone()
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout).withReadTimeout(readTimeout)))
                .build();
        return new ClientCredentialsTokenProvider(tokenClient, tokenUri, clientId, clientCredential, Clock.systemUTC());
    }

    @Bean
    CustomerCreditProfilePort customerCreditProfilePort(RestClient customerRestClient, BearerTokenProvider customerTokenProvider) {
        return new CustomerCreditClient(customerRestClient, customerTokenProvider);
    }
}
