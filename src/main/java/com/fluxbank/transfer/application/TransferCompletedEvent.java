package com.fluxbank.transfer.application;

import java.util.UUID;

public record TransferCompletedEvent(UUID transferPublicId) {
}
