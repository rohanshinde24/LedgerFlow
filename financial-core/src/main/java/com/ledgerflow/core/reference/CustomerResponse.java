package com.ledgerflow.core.reference;

import java.util.UUID;

public record CustomerResponse(UUID id, String name, String email) {
}
