package com.bi1kbu.qslmanagement;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class QslManagementPlugin extends BasePlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(QslManagementPlugin.class);
    private static final String DEFAULT_SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String DEFAULT_STATION_PROFILE_NAME = "qsl-station-profile-default";
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));

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
            .then(ensureDefaultStationProfile())
            .then(migrateCardRemarksFromLegacyCreatedRemarks());
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
                spec.setCardRecordSequence(1000);
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

    private Mono<Void> migrateCardRemarksFromLegacyCreatedRemarks() {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> shouldMigrateCardRemarks(cardRecord.getSpec()))
            .concatMap(cardRecord -> {
                var spec = cardRecord.getSpec();
                spec.setCardRemarks(spec.getCreatedRemarks().trim());
                spec.setCreatedRemarks("");
                cardRecord.setSpec(spec);
                return client.update(cardRecord).then();
            })
            .count()
            .doOnNext(count -> {
                if (count > 0) {
                    LOGGER.info("已迁移 {} 条历史卡片备注数据（createdRemarks -> cardRemarks）。", count);
                }
            })
            .then();
    }

    private boolean shouldMigrateCardRemarks(CardRecord.CardRecordSpec spec) {
        if (spec == null) {
            return false;
        }
        var cardRemarks = safeTrim(spec.getCardRemarks());
        var createdRemarks = safeTrim(spec.getCreatedRemarks());
        var sentRemarks = safeTrim(spec.getSentRemarks());
        var receivedRemarks = safeTrim(spec.getReceivedRemarks());
        var publicReceiptRemarks = safeTrim(spec.getPublicReceiptRemarks());
        return cardRemarks.isBlank()
            && !createdRemarks.isBlank()
            && sentRemarks.isBlank()
            && receivedRemarks.isBlank()
            && publicReceiptRemarks.isBlank();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
