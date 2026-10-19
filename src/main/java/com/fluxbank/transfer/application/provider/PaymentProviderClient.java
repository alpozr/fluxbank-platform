package com.fluxbank.transfer.application.provider;

import java.math.BigDecimal;

public interface PaymentProviderClient {

    PaymentProviderResult sendPayment(
            PaymentProviderRequest request
    );
}