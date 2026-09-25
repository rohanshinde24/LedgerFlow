package com.ledgerflow.core.transaction;

import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    static Specification<Transaction> matching(TransactionQuery query) {
        List<Specification<Transaction>> specifications = new ArrayList<>();
        specifications.add((root, criteriaQuery, builder) ->
                builder.equal(root.get("business").get("id"), query.businessId()));

        if (query.accountId() != null) {
            specifications.add((root, criteriaQuery, builder) ->
                    builder.equal(root.get("account").get("id"), query.accountId()));
        }
        if (query.from() != null) {
            specifications.add((root, criteriaQuery, builder) ->
                    builder.greaterThanOrEqualTo(root.get("bookedDate"), query.from()));
        }
        if (query.to() != null) {
            specifications.add((root, criteriaQuery, builder) ->
                    builder.lessThanOrEqualTo(root.get("bookedDate"), query.to()));
        }
        if (query.categorizationStatus() != null) {
            specifications.add((root, criteriaQuery, builder) ->
                    builder.equal(root.get("categorizationStatus"), query.categorizationStatus()));
        }
        if (query.searchText() != null && !query.searchText().isBlank()) {
            String pattern = "%" + query.searchText().toLowerCase() + "%";
            specifications.add((root, criteriaQuery, builder) -> builder.or(
                    builder.like(builder.lower(root.get("description")), pattern),
                    builder.like(builder.lower(root.get("counterpartyRaw")), pattern)));
        }
        return Specification.allOf(specifications);
    }
}
