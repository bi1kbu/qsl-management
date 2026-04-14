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
    kind = "QsoRecord",
    plural = "qso-records",
    singular = "qso-record"
)
public class QsoRecord extends QslBaseExtension<QsoRecord.QsoRecordSpec, QsoRecord.QsoRecordStatus> {

    @Data
    public static class QsoRecordSpec {
        private String date;
        private String time;
        private String timezone;
        private String freq;
        private String myRig;
        private String myRigMode;
        private String myRigAnt;
        private String myRigPwr;
        private String callSign;
        private String rig;
        private String ant;
        private String pwr;
        private String qth;
        private String rstSent;
        private String rstRcvd;
        private String remarks;
    }

    @Data
    public static class QsoRecordStatus {
        private Boolean autoCreated;
        private String source;
    }
}

