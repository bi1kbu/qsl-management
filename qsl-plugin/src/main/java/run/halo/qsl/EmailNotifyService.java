package run.halo.qsl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

@Service
public class EmailNotifyService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyService.class);

    private static final String REASON_EXCHANGE_REVIEWED = "qsl-exchange-reviewed";
    private static final String REASON_CARD_SENT = "qsl-card-send-confirmed";
    private static final String REASON_CARD_RECEIVED = "qsl-card-receive-confirmed";

    private final ReactiveExtensionClient client;
    private final NotificationCenter notificationCenter;
    private final NotificationReasonEmitter notificationReasonEmitter;

    public EmailNotifyService(ReactiveExtensionClient client, NotificationCenter notificationCenter,
        NotificationReasonEmitter notificationReasonEmitter) {
        this.client = client;
        this.notificationCenter = notificationCenter;
        this.notificationReasonEmitter = notificationReasonEmitter;
    }

    public Map<String, Object> notifyExchangeRequestReviewed(String toEmail, String stationCallsign, String callsign,
        boolean approved, String reason, String reviewedAt, Map<String, String> authHeaders) {
        var attrs = new LinkedHashMap<String, Object>();
        attrs.put("stationCallsign", Objects.toString(stationCallsign, ""));
        attrs.put("callsign", Objects.toString(callsign, ""));
        attrs.put("reviewedAt", Objects.toString(reviewedAt, ""));
        attrs.put("result", approved ? "已通过" : "未通过");
        attrs.put("reason", Objects.toString(reason, ""));
        var subject = "QSL 换卡申请审核结果";
        var definition = buildExchangeDefinition();
        return notifyNative(toEmail, definition, subject, attrs, false);
    }

    public Map<String, Object> notifyCardSendConfirmed(String toEmail, String stationCallsign, String callsign,
        String cardId, String sentAt, Map<String, String> authHeaders) {
        var attrs = new LinkedHashMap<String, Object>();
        attrs.put("stationCallsign", Objects.toString(stationCallsign, ""));
        attrs.put("callsign", Objects.toString(callsign, ""));
        attrs.put("cardId", Objects.toString(cardId, ""));
        attrs.put("sentAt", Objects.toString(sentAt, ""));
        var subject = "QSL 卡片发信状态更新";
        var definition = buildCardSentDefinition();
        return notifyNative(toEmail, definition, subject, attrs, true);
    }

    public Map<String, Object> notifyCardReceiveConfirmed(String toEmail, String stationCallsign, String callsign,
        String cardId, String receivedAt, Map<String, String> authHeaders) {
        var attrs = new LinkedHashMap<String, Object>();
        attrs.put("stationCallsign", Objects.toString(stationCallsign, ""));
        attrs.put("callsign", Objects.toString(callsign, ""));
        attrs.put("cardId", Objects.toString(cardId, ""));
        attrs.put("receivedAt", Objects.toString(receivedAt, ""));
        var subject = "QSL 卡片收信状态更新";
        var definition = buildCardReceivedDefinition();
        return notifyNative(toEmail, definition, subject, attrs, true);
    }

    private Map<String, Object> notifyNative(String toEmail, NotificationDefinition definition,
        String subjectTitle, Map<String, Object> attributes, boolean skipWhenEmailBlank) {
        var result = new LinkedHashMap<String, Object>();
        var to = Objects.toString(toEmail, "").trim();
        if (to.isBlank()) {
            if (skipWhenEmailBlank) {
                result.put("mailSent", false);
                result.put("mailSkipped", true);
                result.put("mailReason", "email is blank");
            } else {
                result.put("mailSent", false);
                result.put("mailError", "email is blank");
            }
            return result;
        }

        try {
            ensureReasonTypeAndTemplate(definition).block();

            var recipientIdentity = UserIdentity.anonymousWithEmail(to);
            var subscriber = new Subscription.Subscriber();
            subscriber.setName(recipientIdentity.name());

            var interestReason = new Subscription.InterestReason();
            interestReason.setReasonType(definition.reasonTypeName());
            interestReason.setSubject(Subscription.ReasonSubject.builder()
                .apiVersion(new GroupVersion(User.GROUP, User.KIND).toString())
                .kind(User.KIND)
                .name(recipientIdentity.name())
                .build());

            var emitMono = notificationReasonEmitter.emit(definition.reasonTypeName(), builder -> {
                builder.author(UserIdentity.of("qsl-management-plugin"))
                    .subject(Reason.Subject.builder()
                        .apiVersion(new GroupVersion(User.GROUP, User.KIND).toString())
                        .kind(User.KIND)
                        .name(recipientIdentity.name())
                        .title(subjectTitle)
                        .build())
                    .attributes(attributes);
            });

            notificationCenter.subscribe(subscriber, interestReason)
                .then(emitMono)
                .then(notificationCenter.unsubscribe(subscriber, interestReason))
                .onErrorResume(ex -> notificationCenter.unsubscribe(subscriber, interestReason)
                    .onErrorResume(ignore -> Mono.empty())
                    .then(Mono.error(ex)))
                .block();

            result.put("mailSent", true);
        } catch (Exception ex) {
            log.warn("Failed to send qsl email by native notification, to={}", to, ex);
            result.put("mailSent", false);
            result.put("mailError", ex.getMessage());
        }
        return result;
    }

    private Mono<Void> ensureReasonTypeAndTemplate(NotificationDefinition definition) {
        return ensureReasonType(definition)
            .then(ensureTemplate(definition));
    }

    private Mono<Void> ensureReasonType(NotificationDefinition definition) {
        return client.fetch(ReasonType.class, definition.reasonTypeName())
            .switchIfEmpty(Mono.defer(() -> client.create(buildReasonType(definition))))
            .then();
    }

    private Mono<Void> ensureTemplate(NotificationDefinition definition) {
        return client.fetch(NotificationTemplate.class, definition.templateName())
            .switchIfEmpty(Mono.defer(() -> client.create(buildNotificationTemplate(definition))))
            .then();
    }

    private ReasonType buildReasonType(NotificationDefinition definition) {
        var reasonType = new ReasonType();
        var metadata = new Metadata();
        metadata.setName(definition.reasonTypeName());
        reasonType.setMetadata(metadata);

        var spec = new ReasonType.Spec();
        spec.setDisplayName(definition.displayName());
        spec.setDescription(definition.description());
        spec.setProperties(definition.properties());
        reasonType.setSpec(spec);
        return reasonType;
    }

    private NotificationTemplate buildNotificationTemplate(NotificationDefinition definition) {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName(definition.templateName());
        template.setMetadata(metadata);

        var spec = new NotificationTemplate.Spec();
        var selector = new NotificationTemplate.ReasonSelector();
        selector.setLanguage("default");
        selector.setReasonType(definition.reasonTypeName());
        spec.setReasonSelector(selector);

        var content = new NotificationTemplate.Template();
        content.setTitle(definition.titleTemplate());
        content.setRawBody(definition.rawBodyTemplate());
        content.setHtmlBody(definition.htmlBodyTemplate());
        spec.setTemplate(content);
        template.setSpec(spec);
        return template;
    }

    private NotificationDefinition buildExchangeDefinition() {
        return new NotificationDefinition(
            REASON_EXCHANGE_REVIEWED,
            "qsl-template-" + REASON_EXCHANGE_REVIEWED,
            "QSL 换卡申请审核结果",
            "用于通知用户其换卡申请审核通过或拒绝。",
            List.of(
                property("stationCallsign", "string", "本台呼号"),
                property("callsign", "string", "呼号"),
                property("reviewedAt", "string", "审核时间"),
                property("result", "string", "审核结果"),
                property("reason", "string", "拒绝原因")
            ),
            "【[(${stationCallsign})]】换卡申请审核结果：[(${result})]（[(${callsign})]）",
            "您好，[(${callsign})]: \n\n"
                + "您的 QSL 换卡申请审核结果为：[(${result})]。\n"
                + "呼号：[(${callsign})]\n"
                + "审核时间：[(${reviewedAt})]\n"
                + "备注：[(${reason})]\n\n"
                + "此邮件由系统自动发送，请勿直接回复。",
            "<div class=\"notification-content\">\n"
                + "<p th:text=\"|您好，${callsign}: |\"></p>\n"
                + "<p th:text=\"|您的 QSL 换卡申请审核结果为：${result}|\"></p>\n"
                + "<p th:text=\"|审核时间：${reviewedAt}|\"></p>\n"
                + "<p th:text=\"|备注：${reason}|\"></p>\n"
                + "<p>此邮件由系统自动发送，请勿直接回复。</p>"
                + "</div>"
        );
    }

    private NotificationDefinition buildCardSentDefinition() {
        return new NotificationDefinition(
            REASON_CARD_SENT,
            "qsl-template-" + REASON_CARD_SENT,
            "QSL 卡片发信状态更新",
            "用于通知用户卡片已发出。",
            List.of(
                property("stationCallsign", "string", "本台呼号"),
                property("callsign", "string", "呼号"),
                property("cardId", "string", "卡片ID"),
                property("sentAt", "string", "发信时间")
            ),
            "【[(${stationCallsign})]】卡片寄送状态更新（[(${callsign})]）",
            "您好，[(${callsign})]: \n\n"
                + "您的 QSL 卡片已完成发信确认，当前状态为“本台已发卡”。\n"
                + "呼号：[(${callsign})]\n"
                + "卡片ID：[(${cardId})]\n"
                + "发信时间：[(${sentAt})]\n\n"
                + "此邮件由系统自动发送，请勿直接回复。",
            "<div class=\"notification-content\">\n"
                + "<p th:text=\"|您好，${callsign}: |\"></p>\n"
                + "<p>您的 QSL 卡片已完成发信确认，当前状态为“本台已发卡”。</p>\n"
                + "<p th:text=\"|呼号：${callsign}|\"></p>\n"
                + "<p th:text=\"|卡片ID：${cardId}|\"></p>\n"
                + "<p th:text=\"|发信时间：${sentAt}|\"></p>\n"
                + "<p>此邮件由系统自动发送，请勿直接回复。</p>"
                + "</div>"
        );
    }

    private NotificationDefinition buildCardReceivedDefinition() {
        return new NotificationDefinition(
            REASON_CARD_RECEIVED,
            "qsl-template-" + REASON_CARD_RECEIVED,
            "QSL 卡片收信状态更新",
            "用于通知用户卡片已收回。",
            List.of(
                property("stationCallsign", "string", "本台呼号"),
                property("callsign", "string", "呼号"),
                property("cardId", "string", "卡片ID"),
                property("receivedAt", "string", "确认时间")
            ),
            "【[(${stationCallsign})]】收信确认状态更新（[(${callsign})]）",
            "您好，[(${callsign})]: \n\n"
                + "您的 QSL 卡片已完成收信确认，当前状态为“已收回卡”。\n"
                + "呼号：[(${callsign})]\n"
                + "卡片ID：[(${cardId})]\n"
                + "确认时间：[(${receivedAt})]\n\n"
                + "此邮件由系统自动发送，请勿直接回复。",
            "<div class=\"notification-content\">\n"
                + "<p th:text=\"|您好，${callsign}: |\"></p>\n"
                + "<p>您的 QSL 卡片已完成收信确认，当前状态为“已收回卡”。</p>\n"
                + "<p th:text=\"|呼号：${callsign}|\"></p>\n"
                + "<p th:text=\"|卡片ID：${cardId}|\"></p>\n"
                + "<p th:text=\"|确认时间：${receivedAt}|\"></p>\n"
                + "<p>此邮件由系统自动发送，请勿直接回复。</p>"
                + "</div>"
        );
    }

    private ReasonType.ReasonProperty property(String name, String type, String description) {
        var p = new ReasonType.ReasonProperty();
        p.setName(name);
        p.setType(type);
        p.setDescription(description);
        p.setOptional(true);
        return p;
    }

    private record NotificationDefinition(
        String reasonTypeName,
        String templateName,
        String displayName,
        String description,
        List<ReasonType.ReasonProperty> properties,
        String titleTemplate,
        String rawBodyTemplate,
        String htmlBodyTemplate
    ) {
    }
}
