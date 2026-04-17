package com.bi1kbu.qslmanagement.extension.model;

import com.bi1kbu.qslmanagement.extension.QslBaseExtension;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "qsl-management.halo.run",
    version = "v1alpha1",
    kind = "SystemSetting",
    plural = "system-settings",
    singular = "system-setting"
)
public class SystemSetting extends QslBaseExtension<SystemSetting.SystemSettingSpec, SystemSetting.SystemSettingStatus> {

    @Data
    public static class SystemSettingSpec {
        private Integer guestQueryPerMinute;
        private Boolean requiresExchangeReview;
        private Boolean autoNotifyOnCardCreated;
        private Boolean autoNotifyOnCardSent;
        private Boolean autoNotifyOnCardReceived;
    }

    @Data
    public static class SystemSettingStatus {
        private String lastModifiedBy;
        private String lastModifiedAt;
    }
}
