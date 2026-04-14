package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslOverviewService {

    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));

    private final ReactiveExtensionClient client;

    public QslOverviewService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<OverviewSummary> calculateSummary() {
        var qsoTotalMono = client.countBy(QsoRecord.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
        var cardListMono = client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();

        return Mono.zip(qsoTotalMono, cardListMono)
            .map(tuple -> {
                var qsoTotal = tuple.getT1();
                var cardRecords = tuple.getT2();
                return buildSummary(qsoTotal, cardRecords);
            });
    }

    private OverviewSummary buildSummary(long qsoTotal, List<CardRecord> cardRecords) {
        long cardTotal = cardRecords.size();
        long eyeballTotal = cardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> "EYEBALL".equalsIgnoreCase(defaultString(cardRecord.getSpec().getCardType())))
            .count();
        long sentTotal = cardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> Boolean.TRUE.equals(cardRecord.getSpec().getCardSent()))
            .count();
        long pendingSendTotal = cardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> !Boolean.TRUE.equals(cardRecord.getSpec().getCardSent()))
            .count();
        long deliverySignedTotal = cardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> Boolean.TRUE.equals(cardRecord.getSpec().getReceiptConfirmed()))
            .count();
        long receivedTotal = cardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> Boolean.TRUE.equals(cardRecord.getSpec().getCardReceived()))
            .count();
        return new OverviewSummary(
            qsoTotal,
            eyeballTotal,
            cardTotal,
            pendingSendTotal,
            sentTotal,
            deliverySignedTotal,
            receivedTotal
        );
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}

