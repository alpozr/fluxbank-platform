package com.fluxbank.transfer.application;

import java.util.UUID;

public interface TransferEventPublisher {
    void publishCompleted(UUID transferPublicId);
}
