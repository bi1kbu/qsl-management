package com.bi1kbu.qslmanagement.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class QslBaseExtension<SPEC, STATUS> extends AbstractExtension {

    private SPEC spec;

    private STATUS status;
}
