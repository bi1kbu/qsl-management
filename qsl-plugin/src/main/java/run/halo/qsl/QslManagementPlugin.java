package run.halo.qsl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(QslManagementPlugin.class);
    private final SchemeManager schemeManager;

    public QslManagementPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        registerPersistentStateScheme();
    }

    @Override
    public void start() {
        log.info("QSL management plugin started");
    }

    @Override
    public void stop() {
        log.info("QSL management plugin stopped");
    }

    private void registerPersistentStateScheme() {
        var gvk = new GroupVersionKind("qsl.halo.run", "v1alpha1", "QslPersistentState");
        schemeManager.fetch(gvk).ifPresent(schemeManager::unregister);
        schemeManager.register(QslPersistentState.class, indexSpecs ->
            indexSpecs.add(
                IndexSpecs.<QslPersistentState, String>single("metadata.name", String.class)
                    .indexFunc(ext -> ext.getMetadata() == null ? null : ext.getMetadata().getName())
                    .unique(true)
                    .nullable(false)
            )
        );
    }
}
