package com.bi1kbu.qslmanagement.api;

import java.util.List;

public record ReportSummary(
    long qsoTotal,
    long eyeballTotal,
    long cardTotal,
    long pendingSendTotal,
    long sentTotal,
    long deliverySignedTotal,
    long receivedTotal,
    ReportCharts charts
) {
    public record ReportCharts(List<MonthlyCardFlowPoint> monthlyCardFlow) {
    }

    public record MonthlyCardFlowPoint(String month, long sentTotal, long receivedTotal) {
    }
}
