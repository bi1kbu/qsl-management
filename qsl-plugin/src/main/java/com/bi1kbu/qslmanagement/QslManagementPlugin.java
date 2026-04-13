package com.bi1kbu.qslmanagement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(QslManagementPlugin.class);

    public QslManagementPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        LOGGER.info("QSL 管理插件启动成功。");
    }

    @Override
    public void stop() {
        LOGGER.info("QSL 管理插件已停止。");
    }
}
