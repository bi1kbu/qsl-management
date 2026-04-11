package run.halo.qsl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(QslManagementPlugin.class);
    private final SchemeManager schemeManager;
    private final Scheme qslStateScheme;
    private final QslDataService qslDataService;

    public QslManagementPlugin(PluginContext pluginContext, SchemeManager schemeManager,
        QslDataService qslDataService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.qslStateScheme = Scheme.buildFromType(QslPluginState.class);
        this.qslDataService = qslDataService;
    }

    @Override
    public void start() {
        schemeManager.register(QslPluginState.class);
        qslDataService.reloadFromPersistentStore();
        log.info("QSL management plugin started");
    }

    @Override
    public void stop() {
        try {
            schemeManager.unregister(qslStateScheme);
        } catch (Exception ex) {
            log.warn("Failed to unregister QslPluginState scheme.", ex);
        }
        log.info("QSL management plugin stopped");
    }
}
