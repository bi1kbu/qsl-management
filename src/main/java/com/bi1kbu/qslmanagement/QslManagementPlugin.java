package com.bi1kbu.qslmanagement;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(QslManagementPlugin.class);
    private static final String DEFAULT_SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String DEFAULT_STATION_PROFILE_NAME = "qsl-station-profile-default";

    private final ReactiveExtensionClient client;

    public QslManagementPlugin(PluginContext pluginContext, ReactiveExtensionClient client) {
        super(pluginContext);
        this.client = client;
    }

    @Override
    public void start() {
        ensureDefaultResources().subscribe(
            ignored -> {
            },
            error -> LOGGER.warn("初始化默认资源失败：{}", error.getMessage())
        );
        LOGGER.info("QSL 管理插件启动成功。");
    }

    @Override
    public void stop() {
        LOGGER.info("QSL 管理插件已停止。");
    }

    private Mono<Void> ensureDefaultResources() {
        return ensureDefaultSystemSetting()
            .then(ensureDefaultStationProfile());
    }

    private Mono<Void> ensureDefaultSystemSetting() {
        return client.fetch(SystemSetting.class, DEFAULT_SYSTEM_SETTING_NAME)
            .then()
            .switchIfEmpty(Mono.defer(() -> {
                var systemSetting = new SystemSetting();
                systemSetting.setMetadata(QslApiSupport.createMetadata(DEFAULT_SYSTEM_SETTING_NAME));
                var spec = new SystemSetting.SystemSettingSpec();
                spec.setGuestQueryPerMinute(30);
                spec.setRequiresExchangeReview(Boolean.TRUE);
                spec.setAutoNotifyOnCardCreated(Boolean.FALSE);
                spec.setAutoNotifyOnCardSent(Boolean.FALSE);
                spec.setAutoNotifyOnCardReceived(Boolean.FALSE);
                spec.setAutoNotifyOnExchangeReviewed(Boolean.FALSE);
                spec.setQsoAutoNotifyOnCardCreated(Boolean.FALSE);
                spec.setQsoAutoNotifyOnCardSent(Boolean.FALSE);
                spec.setQsoAutoNotifyOnCardReceived(Boolean.FALSE);
                spec.setOnlineAutoNotifyOnCardCreated(Boolean.FALSE);
                spec.setOnlineAutoNotifyOnCardSent(Boolean.FALSE);
                spec.setOnlineAutoNotifyOnCardReceived(Boolean.FALSE);
                spec.setOnlineAutoNotifyOnExchangeReviewed(Boolean.FALSE);
                spec.setOfflineAutoNotifyOnCardReceived(Boolean.FALSE);
                spec.setCardRecordSequence(1000);
                spec.setReceiveRecordSequence(0);
                systemSetting.setSpec(spec);
                return client.create(systemSetting)
                    .then()
                    .onErrorResume(this::isDuplicateNameError, ignored -> Mono.empty());
            }));
    }

    private Mono<Void> ensureDefaultStationProfile() {
        return client.fetch(StationProfile.class, DEFAULT_STATION_PROFILE_NAME)
            .then()
            .switchIfEmpty(Mono.defer(() -> {
                var stationProfile = new StationProfile();
                stationProfile.setMetadata(QslApiSupport.createMetadata(DEFAULT_STATION_PROFILE_NAME));
                var spec = new StationProfile.StationProfileSpec();
                spec.setMyCallSign("");
                spec.setMyName("");
                spec.setMyTelephone("");
                spec.setMyPostalCode("");
                spec.setMyAddress("");
                spec.setMyEmail("");
                spec.setStationRemarks("");
                stationProfile.setSpec(spec);
                return client.create(stationProfile)
                    .then()
                    .onErrorResume(this::isDuplicateNameError, ignored -> Mono.empty());
            }));
    }

    private boolean isDuplicateNameError(Throwable error) {
        return error != null
            && error.getMessage() != null
            && error.getMessage().contains("Duplicate name detected.");
    }

}
