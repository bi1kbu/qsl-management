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
    kind = "AddressBookEntry",
    plural = "address-book-entries",
    singular = "address-book-entry"
)
public class AddressBookEntry extends QslBaseExtension<AddressBookEntry.AddressBookSpec, AddressBookEntry.AddressBookStatus> {

    @Data
    public static class AddressBookSpec {
        private String callSign;
        private String name;
        private String telephone;
        private String postalCode;
        private String destinationCountry;
        private String address;
        private String email;
        private String addressRemarks;
    }

    @Data
    public static class AddressBookStatus {
        private String syncStatus;
    }
}
