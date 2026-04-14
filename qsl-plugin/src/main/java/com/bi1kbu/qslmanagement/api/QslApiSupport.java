package com.bi1kbu.qslmanagement.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import run.halo.app.extension.Metadata;

public final class QslApiSupport {

    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss"
    );
    private static final DateTimeFormatter UTC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter UTC_TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmm");

    private QslApiSupport() {
    }

    public static String nowText() {
        return LOCAL_DATE_TIME_FORMATTER.format(ZonedDateTime.now());
    }

    public static String utcDate() {
        return UTC_DATE_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    public static String utcTime() {
        return UTC_TIME_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    public static String normalizeCardType(String cardType) {
        if (cardType == null || cardType.isBlank()) {
            return "QSO";
        }
        return cardType.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeCallSign(String callSign) {
        if (callSign == null) {
            return "";
        }
        return callSign.trim().toUpperCase(Locale.ROOT);
    }

    public static String appendRemark(String origin, String appended) {
        var safeOrigin = origin == null ? "" : origin.trim();
        var safeAppend = appended == null ? "" : appended.trim();
        if (safeAppend.isBlank()) {
            return safeOrigin;
        }
        if (safeOrigin.isBlank()) {
            return safeAppend;
        }
        return safeOrigin + "；" + safeAppend;
    }

    public static Metadata createMetadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }

    public static String createResourceName(String prefix) {
        var randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "-" + Instant.now().toEpochMilli() + "-" + randomPart;
    }
}
