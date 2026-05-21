package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    public Mono<ReportSummary> calculateReportSummary() {
        var qsoTotalMono = client.countBy(QsoRecord.class, EMPTY_OPTIONS).defaultIfEmpty(0L);
        var cardListMono = client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();
        var receiveListMono = client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();

        return Mono.zip(qsoTotalMono, cardListMono, receiveListMono)
            .map(tuple -> {
                var qsoTotal = tuple.getT1();
                var cardRecords = tuple.getT2();
                var receiveRecords = tuple.getT3();
                return buildReportSummary(qsoTotal, cardRecords, receiveRecords);
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

    private ReportSummary buildReportSummary(long qsoTotal, List<CardRecord> cardRecords,
        List<ReceiveRecord> receiveRecords) {
        var summary = buildSummary(qsoTotal, cardRecords, receiveRecords);
        var filteredCardRecords = cardRecords.stream()
            .filter(this::includeInCardStatistics)
            .toList();
        return new ReportSummary(
            summary.qsoTotal(),
            summary.eyeballTotal(),
            summary.cardTotal(),
            summary.pendingSendTotal(),
            summary.sentTotal(),
            summary.deliverySignedTotal(),
            summary.receivedTotal(),
            new ReportSummary.ReportCharts(monthlyCardFlow(filteredCardRecords, receiveRecords))
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
            && !isReceivedFlowStatus(cardRecord)
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
            .filter(this::isReceivedFlowStatus)
            .map(cardRecord -> normalizeKey(cardRecord.getSpec().getCallSign()))
            .filter(value -> !value.isBlank())
            .distinct()
            .count();
    }

    private boolean isReceivedFlowStatus(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getStatus() == null) {
            return false;
        }
        return "已收卡片".equals(defaultString(cardRecord.getStatus().getFlowStatus()).trim());
    }

    private List<ReportSummary.MonthlyCardFlowPoint> monthlyCardFlow(List<CardRecord> filteredCardRecords,
        List<ReceiveRecord> receiveRecords) {
        var firstMonth = filteredCardRecords.stream()
            .map(this::cardStatisticMonth)
            .flatMap(List::stream)
            .min(YearMonth::compareTo)
            .orElse(YearMonth.now());
        var currentMonth = YearMonth.now();
        if (firstMonth.isAfter(currentMonth)) {
            firstMonth = currentMonth;
        }
        Map<YearMonth, MonthlyCounter> counters = new LinkedHashMap<>();
        var cursor = firstMonth;
        while (!cursor.isAfter(currentMonth)) {
            counters.put(cursor, new MonthlyCounter());
            cursor = cursor.plusMonths(1);
        }

        filteredCardRecords.stream()
            .filter(this::includeInSentStatistics)
            .forEach(cardRecord -> resolveSentMonth(cardRecord).ifPresent(month -> {
                var counter = counters.get(month);
                if (counter != null) {
                    counter.sentTotal++;
                }
            }));
        Map<YearMonth, Set<String>> receiveKeysByMonth = receiveRecords.stream()
            .filter(receiveRecord -> receiveRecord != null && receiveRecord.getSpec() != null)
            .flatMap(receiveRecord -> resolveReceiveMonth(receiveRecord)
                .stream()
                .flatMap(month -> receivedStatisticKeys(receiveRecord).stream()
                    .map(key -> new MonthlyReceiveKey(month, key))))
            .collect(Collectors.groupingBy(
                MonthlyReceiveKey::month,
                Collectors.mapping(MonthlyReceiveKey::key, Collectors.toSet())
            ));
        receiveKeysByMonth.forEach((month, receiveKeys) -> {
            var counter = counters.get(month);
            if (counter != null) {
                counter.receivedTotal += receiveKeys.size();
            }
        });

        return counters.entrySet().stream()
            .map(entry -> new ReportSummary.MonthlyCardFlowPoint(
                entry.getKey().toString(),
                entry.getValue().sentTotal,
                entry.getValue().receivedTotal
            ))
            .toList();
    }

    private List<YearMonth> cardStatisticMonth(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getSpec() == null) {
            return List.of();
        }
        var values = new ArrayList<YearMonth>();
        parseMonth(cardRecord.getSpec().getCardDate()).ifPresent(values::add);
        parseMonth(cardRecord.getSpec().getSentAt()).ifPresent(values::add);
        return values;
    }

    private Optional<YearMonth> resolveSentMonth(CardRecord cardRecord) {
        if (cardRecord == null || cardRecord.getSpec() == null) {
            return Optional.empty();
        }
        var spec = cardRecord.getSpec();
        return parseMonth(spec.getSentAt()).or(() -> parseMonth(spec.getCardDate()));
    }

    private Optional<YearMonth> resolveReceiveMonth(ReceiveRecord receiveRecord) {
        if (receiveRecord == null || receiveRecord.getSpec() == null) {
            return Optional.empty();
        }
        var spec = receiveRecord.getSpec();
        return parseMonth(spec.getReceivedDate()).or(() -> parseMonth(spec.getReceivedAt()));
    }

    private Optional<YearMonth> parseMonth(String value) {
        var text = defaultString(value).trim();
        if (text.length() < 7) {
            return Optional.empty();
        }
        var dateText = text.length() >= 10 ? text.substring(0, 10) : text + "-01";
        try {
            return Optional.of(YearMonth.from(LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE)));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
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

    private static class MonthlyCounter {
        private long sentTotal;
        private long receivedTotal;
    }

    private record MonthlyReceiveKey(YearMonth month, String key) {
    }
}
