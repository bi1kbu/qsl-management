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
    kind = "QslCardRequestQsoReservation",
    plural = "qsl-card-request-qso-reservations",
    singular = "qsl-card-request-qso-reservation"
)
public class QslCardRequestQsoReservation extends QslBaseExtension<
    QslCardRequestQsoReservation.QslCardRequestQsoReservationSpec,
    QslCardRequestQsoReservation.QslCardRequestQsoReservationStatus> {

    @Data
    public static class QslCardRequestQsoReservationSpec {
        private String requestName;
        private String qsoRecordName;
        private String createdAt;
    }

    @Data
    public static class QslCardRequestQsoReservationStatus {
        private String state;
    }
}
