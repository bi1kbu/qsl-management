package run.halo.qsl;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(group = QslPluginState.GROUP,
    version = QslPluginState.VERSION,
    kind = QslPluginState.KIND,
    singular = "qslstatestore",
    plural = "qslstatestores")
public class QslPluginState extends AbstractExtension {

    public static final String GROUP = "qsl.halo.run";
    public static final String VERSION = "v1alpha1";
    public static final String KIND = "QslStateStore";
    public static final String STORAGE_NAME = "qsl-storage";

    private Spec spec = new Spec();

    @Data
    public static class Spec {
        // Keep schema simple to avoid strict nested-map validation issues.
        private String payloadJson = "{}";
    }
}
