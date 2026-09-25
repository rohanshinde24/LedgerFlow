package com.ledgerflow.core.transaction;

import com.ledgerflow.core.common.PagedResponse;
import com.ledgerflow.core.common.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<TransactionResponse> search(TransactionQuery query, Pageable pageable) {
        return PagedResponse.from(
                transactionRepository.findAll(TransactionSpecifications.matching(query), pageable),
                TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse findById(UUID id) {
        return TransactionResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public CounterpartyHistoryResponse counterpartyHistory(UUID id, int exampleLimit) {
        Transaction transaction = require(id);
        UUID businessId = transaction.getBusiness().getId();
        String counterparty = transaction.getCounterpartyRaw();

        List<TransactionResponse> examples = transactionRepository
                .findByCounterparty(businessId, counterparty, PageRequest.of(0, exampleLimit)).stream()
                .filter(candidate -> !candidate.getId().equals(id))
                .map(TransactionResponse::from)
                .toList();

        return CounterpartyHistoryResponse.of(counterparty,
                transactionRepository.summarizeCounterpartyHistory(businessId, counterparty), examples);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> offsettingCandidates(UUID id, int windowDays) {
        Transaction transaction = require(id);
        return transactionRepository.findOffsettingInOtherAccounts(
                        transaction.getBusiness().getId(),
                        id,
                        transaction.getAccount().getId(),
                        transaction.getAmount().amount().negate(),
                        transaction.getBookedDate().minusDays(windowDays),
                        transaction.getBookedDate().plusDays(windowDays)).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> similarCandidates(UUID id, int windowDays) {
        Transaction transaction = require(id);
        return transactionRepository.findSameCounterpartyAndAmount(
                        transaction.getBusiness().getId(),
                        id,
                        transaction.getCounterpartyRaw(),
                        transaction.getAmount().amount(),
                        transaction.getBookedDate().minusDays(windowDays),
                        transaction.getBookedDate().plusDays(windowDays)).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    private Transaction require(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }
}
