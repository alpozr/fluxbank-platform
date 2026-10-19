package com.fluxbank.transfer.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentProviderConfig {

    @Bean
    public RestClient paymentProviderRestClient(
            @Value("${payment-provider.base-url}") String baseUrl
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}