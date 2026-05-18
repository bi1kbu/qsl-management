package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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
        var receiveListMono = client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();

        return Mono.zip(qsoTotalMono, cardListMono, receiveListMono)
            .map(tuple -> {
                var qsoTotal = tuple.getT1();
                var cardRecords = tuple.getT2();
                var receiveRecords = tuple.getT3();
                return buildSummary(qsoTotal, cardRecords, receiveRecords);
            });
    }

    private OverviewSummary buildSummary(long qsoTotal, List<CardRecord> cardRecords, List<ReceiveRecord> receiveRecords) {
        var filteredCardRecords = cardRecords.stream()
            .filter(this::includeInCardStatistics)
            .toList();
        var linkedOutboundCardNames = linkedOutboundCardNames(receiveRecords);
        long cardTotal = filteredCardRecords.size();
        long eyeballTotal = filteredCardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> "EYEBALL".equalsIgnoreCase(defaultString(cardRecord.getSpec().getCardType())))
            .count();
        long sentTotal = filteredCardRecords.stream()
            .filter(this::includeInSentStatistics)
            .count();
        long pendingSendTotal = filteredCardRecords.stream()
            .filter(cardRecord -> includeInPendingSendStatistics(cardRecord, linkedOutboundCardNames))
            .count();
        long deliverySignedTotal = filteredCardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> Boolean.TRUE.equals(cardRecord.getSpec().getReceiptConfirmed()))
            .count();
        long receivedTotal = receivedTotal(filteredCardRecords, receiveRecords);
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

    private boolean includeInCardStatistics(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getSpec() == null || cardRecord.getMetadata() == null) {
            return false;
        }
        var resourceName = defaultString(cardRecord.getMetadata().getName()).trim().toUpperCase(Locale.ROOT);
        var callSign = defaultString(cardRecord.getSpec().getCallSign()).trim();
        return resourceName.matches("^C\\d+$") && !callSign.isBlank();
    }

    private boolean includeInPendingSendStatistics(CardRecord cardRecord, Set<String> linkedOutboundCardNames) {
        if (!includeInCardStatistics(cardRecord)) {
            return false;
        }
        var spec = cardRecord.getSpec();
        var sceneType = defaultString(spec.getSceneType()).trim().toUpperCase(Locale.ROOT);
        var resourceName = defaultString(cardRecord.getMetadata().getName()).trim().toUpperCase(Locale.ROOT);
        return !"ONLINE_EYEBALL".equals(sceneType)
            && !Boolean.TRUE.equals(spec.getCardSent())
            && !Boolean.TRUE.equals(spec.getCardReceived())
            && !Boolean.TRUE.equals(spec.getReceiptConfirmed())
            && !linkedOutboundCardNames.contains(resourceName);
    }

    private boolean includeInSentStatistics(CardRecord cardRecord) {
        if (!includeInCardStatistics(cardRecord)) {
            return false;
        }
        var spec = cardRecord.getSpec();
        var sceneType = defaultString(spec.getSceneType()).trim().toUpperCase(Locale.ROOT);
        var sentAt = defaultString(spec.getSentAt()).trim();
        return Boolean.TRUE.equals(spec.getCardSent())
            || Boolean.TRUE.equals(spec.getReceiptConfirmed())
            || !sentAt.isBlank()
            || ("ONLINE_EYEBALL".equals(sceneType)
                && Boolean.TRUE.equals(spec.getCardIssued())
                && Boolean.TRUE.equals(spec.getEnvelopePrinted())
                && "SENT".equalsIgnoreCase(defaultString(spec.getCreatedMailStatus()).trim()));
    }

    private Set<String> linkedOutboundCardNames(List<ReceiveRecord> receiveRecords) {
        return receiveRecords.stream()
            .filter(receiveRecord -> receiveRecord != null && receiveRecord.getSpec() != null)
            .flatMap(receiveRecord -> splitNames(receiveRecord.getSpec().getOutboundCardNames()).stream())
            .collect(Collectors.toSet());
    }

    private long receivedTotal(List<CardRecord> filteredCardRecords, List<ReceiveRecord> receiveRecords) {
        var receiveKeys = receiveRecords.stream()
            .filter(receiveRecord -> receiveRecord != null && receiveRecord.getSpec() != null)
            .flatMap(receiveRecord -> receivedStatisticKeys(receiveRecord).stream())
            .collect(Collectors.toSet());
        if (!receiveKeys.isEmpty()) {
            return receiveKeys.size();
        }
        return filteredCardRecords.stream()
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> Boolean.TRUE.equals(cardRecord.getSpec().getCardReceived()))
            .map(cardRecord -> normalizeKey(cardRecord.getSpec().getCallSign()))
            .filter(value -> !value.isBlank())
            .distinct()
            .count();
    }

    private List<String> receivedStatisticKeys(ReceiveRecord receiveRecord) {
        var spec = receiveRecord.getSpec();
        var callSign = normalizeKey(spec.getCallSign());
        if (!callSign.isBlank()) {
            return List.of("CALL:" + callSign);
        }
        return splitNames(spec.getOutboundCardNames()).stream()
            .map(name -> "CARD:" + name)
            .toList();
    }

    private List<String> splitNames(String value) {
        return Arrays.stream(defaultString(value).split("[,，、;；\\s]+"))
            .map(this::normalizeKey)
            .filter(name -> !name.isBlank())
            .toList();
    }

    private String normalizeKey(String value) {
        return defaultString(value).trim().toUpperCase(Locale.ROOT);
    }
}
