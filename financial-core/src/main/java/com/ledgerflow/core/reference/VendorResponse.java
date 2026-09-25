package com.ledgerflow.core.reference;

import java.util.UUID;

public record VendorResponse(UUID id, String name, String defaultChartOfAccountCode, String defaultChartOfAccountName) {
}
