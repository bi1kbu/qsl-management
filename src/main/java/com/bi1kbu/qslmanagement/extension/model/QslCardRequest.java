package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.bi1kbu.com",
    version = "v1alpha1",
    kind = "QslCardRequest",
    plural = "qsl-card-requests",
    singular = "qsl-card-request"
)
public class QslCardRequest
    extends QslBaseExtension<QslCardRequest.QslCardRequestSpec, QslCardRequest.QslCardRequestStatus> {

    @Data
    public static class QslCardRequestSpec {
        private String callSign;
        private List<QsoItem> qsoItems = new ArrayList<>();
        private String addressType;
        private String addressEntryName;
        private String notificationEmail;
        private String remarks;
        private String submittedAt;
    }

    @Data
    public static class QsoItem {
        private String qsoRecordName;
        private String cardVersion;
    }

    @Data
    public static class QslCardRequestStatus {
        private String reviewStatus;
        private String reviewReason;
        private String reviewedBy;
        private String reviewedAt;
        private String cardCreationStatus;
        private List<CreatedCardItem> createdCards = new ArrayList<>();
        private String reviewMailStatus;
        private String reviewMailSentAt;
        private String reviewMailLastError;
        private String reviewMailTargetEmail;
    }

    @Data
    public static class CreatedCardItem {
        private String qsoRecordName;
        private String cardVersion;
        private String cardRecordName;
        private String creationStatus;
        private String lastError;
    }
}
