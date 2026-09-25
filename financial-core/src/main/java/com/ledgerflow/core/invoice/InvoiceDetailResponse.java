package com.ledgerflow.core.invoice;

import com.ledgerflow.core.common.MoneyView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceDetailResponse(InvoiceResponse invoice, List<Line> lines) {

    public record Line(UUID id, int lineNumber, String description, BigDecimal quantity, MoneyView unitPrice,
                       MoneyView lineTotal) {
    }

    public static InvoiceDetailResponse from(Invoice invoice, LocalDate asOf) {
        List<Line> lines = invoice.getLines().stream()
                .map(line -> new Line(line.getId(), line.getLineNumber(), line.getDescription(), line.getQuantity(),
                        MoneyView.of(line.getUnitPrice()), MoneyView.of(line.getLineTotal())))
                .toList();
        return new InvoiceDetailResponse(InvoiceResponse.from(invoice, asOf), lines);
    }
}
