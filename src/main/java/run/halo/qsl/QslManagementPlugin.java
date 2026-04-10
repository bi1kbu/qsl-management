package run.halo.qsl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(QslManagementPlugin.class);

    public QslManagementPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("QSL management plugin started");
    }

    @Override
    public void stop() {
        log.info("QSL management plugin stopped");
    }
}
