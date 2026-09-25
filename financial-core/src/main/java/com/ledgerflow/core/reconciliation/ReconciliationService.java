package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.common.ResourceNotFoundException;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.payment.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationService {

    private final PaymentRepository paymentRepository;
    private final ReconciliationMatchRepository matchRepository;
    private final ReconciliationCandidateFinder candidateFinder;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 ReconciliationMatchRepository matchRepository,
                                 ReconciliationCandidateFinder candidateFinder) {
        this.paymentRepository = paymentRepository;
        this.matchRepository = matchRepository;
        this.candidateFinder = candidateFinder;
    }

    @Transactional(readOnly = true)
    public ReconciliationCandidateSet findCandidates(UUID paymentId) {
        Payment payment = paymentRepository.findDetailById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        return candidateFinder.findCandidates(payment, unappliedAmount(payment));
    }

    @Transactional(readOnly = true)
    public Money unappliedAmount(Payment payment) {
        return payment.getAmount().minus(confirmedTotal(matchRepository.findByPaymentIdAndStatus(
                payment.getId(), MatchStatus.CONFIRMED), payment.getCurrency()));
    }

    /**
     * Invariants 2 and 3: paid/applied amounts are always recomputed from confirmed matches inside the
     * same transaction that changed them, so stored balances cannot drift from the match ledger.
     */
    @Transactional
    public void recomputeAllocations(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        List<ReconciliationMatch> paymentMatches = matchRepository.findByPaymentId(paymentId);

        payment.applyConfirmedAllocations(confirmedTotal(paymentMatches, payment.getCurrency()));

        Map<UUID, Invoice> touchedInvoices = new LinkedHashMap<>();
        paymentMatches.forEach(match -> touchedInvoices.put(match.getInvoice().getId(), match.getInvoice()));

        touchedInvoices.values().forEach(invoice -> invoice.applyConfirmedAllocations(
                confirmedTotal(matchRepository.findByInvoiceId(invoice.getId()), invoice.getCurrency())));
    }

    private Money confirmedTotal(List<ReconciliationMatch> matches, Currency currency) {
        return matches.stream()
                .filter(match -> match.getStatus() == MatchStatus.CONFIRMED)
                .map(ReconciliationMatch::getAmountApplied)
                .reduce(Money.zero(currency), Money::plus);
    }
}
