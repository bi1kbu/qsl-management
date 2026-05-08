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
    kind = "OfflineActivity",
    plural = "offline-activities",
    singular = "offline-activity"
)
public class OfflineActivity extends QslBaseExtension<OfflineActivity.OfflineActivitySpec, OfflineActivity.OfflineActivityStatus> {

    @Data
    public static class OfflineActivitySpec {
        private String activityName;
        private String activityLocation;
        private String activityDate;
        private String activityTime;
        private String cardRemarks;
    }

    @Data
    public static class OfflineActivityStatus {
        private String workflowStatus;
    }
}
