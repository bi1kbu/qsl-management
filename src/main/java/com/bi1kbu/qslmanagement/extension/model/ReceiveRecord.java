package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.bi1kbu.com",
    version = "v1alpha1",
    kind = "ReceiveRecord",
    plural = "receive-records",
    singular = "receive-record"
)
public class ReceiveRecord extends QslBaseExtension<ReceiveRecord.ReceiveRecordSpec, ReceiveRecord.ReceiveRecordStatus> {

    @Data
    public static class ReceiveRecordSpec {
        private String callSign;
        private String cardType;
        private String businessType;
        private String offlineActivityName;
        private String receivedDate;
        private String receivedAt;
        private String outboundCardNames;
        private String matchStatus;
        private String matchReason;
        private String remarks;
    }

    @Data
    public static class ReceiveRecordStatus {
        private String syncStatus;
    }
}
