package com.bi1kbu.qslmanagement.api;

public record OverviewSummary(
    long qsoTotal,
    long eyeballTotal,
    long cardTotal,
    long pendingSendTotal,
    long sentTotal,
    long deliverySignedTotal,
    long receivedTotal
) {
}

