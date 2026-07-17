package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QslCardRequest;
import com.bi1kbu.qslmanagement.extension.model.QslCardRequestQsoReservation;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.StationCard;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslCardRequestService {

    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$");
    private static final Pattern TELEPHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s]{1,30}$");
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9\\-]{1,20}$");
    private static final Pattern REQUEST_NAME_PATTERN = Pattern.compile("^QCR(\\d{4,})$");
    private static final String STATION_PROFILE_NAME = "qsl-station-profile-default";
    private static final String REVIEW_PENDING = "待处理";
    private static final String REVIEW_APPROVED = "通过";
    private static final String REVIEW_REJECTED = "拒绝";
    private static final String CARD_STATUS_NOT_CREATED = "未创建";
    private static final String CARD_STATUS_CREATING = "创建中";
    private static final String CARD_STATUS_SUCCESS = "全部成功";
    private static final String CARD_STATUS_PARTIAL = "部分失败";
    private static final int MAX_QSO_ITEMS = 20;

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;
    private final QslConsoleActionService consoleActionService;
    private final QslNotificationMailService notificationMailService;
    private final AtomicBoolean submissionLock = new AtomicBoolean(false);

    public QslCardRequestService(
        ReactiveExtensionClient client,
        QslAuditService qslAuditService,
        QslConsoleActionService consoleActionService,
        QslNotificationMailService notificationMailService
    ) {
        this.client = client;
        this.qslAuditService = qslAuditService;
        this.consoleActionService = consoleActionService;
        this.notificationMailService = notificationMailService;
    }

    public Mono<PublicQsoEligibilityResult> listEligibleQso(String rawCallSign) {
        var callSign = normalizeAndValidateCallSign(rawCallSign);
        return Mono.zip(
            client.listAll(QsoRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(item -> item.getMetadata() != null && item.getSpec() != null)
                .filter(item -> callSign.equals(QslApiSupport.normalizeCallSign(item.getSpec().getCallSign())))
                .filter(item -> "QSO".equals(normalizeQsoSceneType(item.getSpec().getSceneType())))
                .collectList(),
            occupiedQsoRecordNames(),
            client.listAll(QslCardRequestQsoReservation.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(item -> item.getSpec() != null)
                .map(item -> normalize(item.getSpec().getQsoRecordName()))
                .filter(item -> !item.isBlank())
                .collect(LinkedHashSet::new, Set::add)
        ).map(tuple -> {
            var cardOccupied = tuple.getT2();
            var pendingOccupied = tuple.getT3();
            var items = tuple.getT1().stream()
                .map(qso -> {
                    var name = normalize(qso.getMetadata().getName());
                    var reason = cardOccupied.contains(name)
                        ? "已有卡片"
                        : pendingOccupied.contains(name) ? "待审核" : "";
                    var spec = qso.getSpec();
                    return new PublicQsoEligibilityItem(
                        name,
                        normalize(spec.getDate()),
                        normalize(spec.getTime()),
                        normalize(spec.getFreq()),
                        normalize(spec.getQth()),
                        reason.isBlank(),
                        reason
                    );
                })
                .toList();
            return new PublicQsoEligibilityResult(callSign, items, items.size());
        });
    }

    public Mono<PublicStationContact> getPublicStationContact() {
        return client.fetch(StationProfile.class, STATION_PROFILE_NAME)
            .map(profile -> profile.getSpec() == null ? "" : normalize(profile.getSpec().getMyEmail()))
            .defaultIfEmpty("")
            .map(PublicStationContact::new);
    }

    public Mono<ReservationReconcileResult> reconcileReservations() {
        return Mono.zip(
            client.listAll(QslCardRequestQsoReservation.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList(),
            client.listAll(QslCardRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(item -> item.getMetadata() != null)
                .collectList()
                .map(items -> {
                    Map<String, QslCardRequest> requests = new LinkedHashMap<>();
                    items.forEach(item -> requests.put(normalize(item.getMetadata().getName()), item));
                    return requests;
                }),
            occupiedQsoRecordNames()
        ).flatMap(tuple -> {
            var reservations = tuple.getT1();
            var requests = tuple.getT2();
            var occupiedQsoNames = tuple.getT3();
            return Flux.fromIterable(reservations)
                .filter(item -> shouldReleaseReservation(item, requests, occupiedQsoNames))
                .concatMap(item -> client.delete(item).thenReturn(item))
                .count()
                .map(released -> new ReservationReconcileResult(
                    reservations.size(), released.intValue(), reservations.size() - released.intValue()
                ));
        });
    }

    public Mono<SubmitResult> submit(SubmitCommand command, String clientIp) {
        var normalized = normalizeAndValidateCommand(command);
        return withSubmissionLock(() -> validateRequestedQsoItems(normalized)
            .flatMap(validatedItems -> resolveAddress(normalized)
                .flatMap(binding -> nextRequestName()
                    .flatMap(requestName -> acquireReservations(requestName, validatedItems)
                        .flatMap(reservations -> createRequest(requestName, normalized, validatedItems, binding)
                            .onErrorResume(error -> releaseReservations(reservations).then(Mono.error(error))))
                    )))
            .flatMap(created -> qslAuditService.appendAuditLog(
                "匿名提交实体QSL卡申请",
                "qsl-card-request",
                created.getMetadata().getName(),
                "呼号=" + created.getSpec().getCallSign() + "；QSO数量=" + created.getSpec().getQsoItems().size(),
                "匿名用户",
                clientIp
            ).thenReturn(created))
            .map(created -> new SubmitResult(
                created.getMetadata().getName(),
                created.getSpec().getCallSign(),
                created.getStatus().getReviewStatus(),
                created.getSpec().getSubmittedAt(),
                created.getSpec().getQsoItems().size()
            )));
    }

    public Mono<ReviewResult> approve(String requestName, String reason, String operator, String clientIp) {
        return fetchRequest(requestName)
            .flatMap(request -> ensurePending(request)
                .then(validateRequestBeforeApproval(request))
                .then(Mono.defer(() -> markApprovedAndCreating(request, reason, operator)))
                .flatMap(updated -> createRemainingCards(updated, operator, clientIp)))
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "实体QSL卡申请审核通过",
                "qsl-card-request",
                updated.getMetadata().getName(),
                "卡片创建状态=" + updated.getStatus().getCardCreationStatus(),
                operator,
                clientIp
            ).thenReturn(updated))
            .flatMap(updated -> notificationMailService.sendQslCardRequestReviewMail(
                    updated.getMetadata().getName(), operator, clientIp, "审核通过自动发送")
                .onErrorResume(error -> Mono.empty())
                .then(client.fetch(QslCardRequest.class, updated.getMetadata().getName()).defaultIfEmpty(updated)))
            .map(this::toReviewResult);
    }

    public Mono<ReviewResult> reject(String requestName, String reason, String operator, String clientIp) {
        return fetchRequest(requestName)
            .flatMap(request -> ensurePending(request).then(Mono.defer(() -> {
                var status = ensureStatus(request);
                status.setReviewStatus(REVIEW_REJECTED);
                status.setReviewReason(normalize(reason).isBlank() ? "审批拒绝" : normalize(reason));
                status.setReviewedBy(safeOperator(operator));
                status.setReviewedAt(QslApiSupport.nowText());
                request.setStatus(status);
                return client.update(request);
            })))
            .flatMap(updated -> releaseReservationsForRequest(updated.getMetadata().getName())
                .then(qslAuditService.appendAuditLog(
                    "实体QSL卡申请审核拒绝",
                    "qsl-card-request",
                    updated.getMetadata().getName(),
                    "原因=" + updated.getStatus().getReviewReason(),
                    operator,
                    clientIp
                )).thenReturn(updated))
            .flatMap(updated -> notificationMailService.sendQslCardRequestReviewMail(
                    updated.getMetadata().getName(), operator, clientIp, "审核拒绝自动发送")
                .onErrorResume(error -> Mono.empty())
                .then(client.fetch(QslCardRequest.class, updated.getMetadata().getName()).defaultIfEmpty(updated)))
            .map(this::toReviewResult);
    }

    public Mono<ReviewResult> retryCardCreation(String requestName, String operator, String clientIp) {
        return fetchRequest(requestName)
            .flatMap(request -> {
                var status = ensureStatus(request);
                if (!REVIEW_APPROVED.equals(normalize(status.getReviewStatus()))) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-QCR-0003", "只有已通过的申请可以重试创建卡片"));
                }
                return validateRequestBeforeRetry(request)
                    .then(Mono.defer(() -> createRemainingCards(request, operator, clientIp)));
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "重试实体QSL卡申请建卡",
                "qsl-card-request",
                updated.getMetadata().getName(),
                "卡片创建状态=" + updated.getStatus().getCardCreationStatus(),
                operator,
                clientIp
            ).thenReturn(updated))
            .map(this::toReviewResult);
    }

    private Mono<QslCardRequest> createRequest(
        String requestName,
        SubmitCommand command,
        List<ValidatedQsoItem> validatedItems,
        AddressBinding binding
    ) {
        var request = new QslCardRequest();
        request.setMetadata(QslApiSupport.createMetadata(requestName));
        var spec = new QslCardRequest.QslCardRequestSpec();
        spec.setCallSign(command.callSign());
        spec.setQsoItems(validatedItems.stream().map(item -> {
            var qsoItem = new QslCardRequest.QsoItem();
            qsoItem.setQsoRecordName(item.qsoRecordName());
            qsoItem.setCardVersion(item.cardVersion());
            return qsoItem;
        }).toList());
        spec.setAddressType(command.addressType());
        spec.setAddressEntryName(binding.resourceName());
        spec.setNotificationEmail(command.notificationEmail());
        spec.setRemarks(command.remarks());
        spec.setSubmittedAt(QslApiSupport.nowText());
        request.setSpec(spec);
        var status = new QslCardRequest.QslCardRequestStatus();
        status.setReviewStatus(REVIEW_PENDING);
        status.setReviewReason("");
        status.setReviewedBy("");
        status.setReviewedAt("");
        status.setCardCreationStatus(CARD_STATUS_NOT_CREATED);
        status.setCreatedCards(new ArrayList<>());
        status.setReviewMailStatus("");
        status.setReviewMailSentAt("");
        status.setReviewMailLastError("");
        status.setReviewMailTargetEmail(command.notificationEmail());
        request.setStatus(status);
        return client.create(request);
    }

    private Mono<List<ValidatedQsoItem>> validateRequestedQsoItems(SubmitCommand command) {
        return Flux.fromIterable(command.qsoItems())
            .concatMap(item -> client.fetch(QsoRecord.class, item.qsoRecordName())
                .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.BAD_REQUEST,
                    "QSL-400-QCR-0001", "QSO记录不存在：" + item.qsoRecordName())))
                .map(qso -> validateQso(command.callSign(), item, qso)))
            .collectList()
            .flatMap(items -> validateNoExistingCardOrReservation(items).then(validateCardVersions(items)));
    }

    private ValidatedQsoItem validateQso(String callSign, RequestedQsoItem requested, QsoRecord qso) {
        if (qso.getSpec() == null || qso.getMetadata() == null) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "QSO记录缺少业务字段");
        }
        if (!callSign.equals(QslApiSupport.normalizeCallSign(qso.getSpec().getCallSign()))) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "QSO记录不属于提交呼号");
        }
        if (!"QSO".equals(normalizeQsoSceneType(qso.getSpec().getSceneType()))) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "仅允许申请QSO场景记录");
        }
        return new ValidatedQsoItem(normalize(qso.getMetadata().getName()), requested.cardVersion());
    }

    private Mono<Void> validateNoExistingCardOrReservation(List<ValidatedQsoItem> items) {
        var names = items.stream().map(ValidatedQsoItem::qsoRecordName)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return occupiedQsoRecordNames().flatMap(occupied -> {
            var conflict = names.stream().filter(occupied::contains).findFirst();
            if (conflict.isPresent()) {
                return Mono.error(new QslApiException(HttpStatus.CONFLICT,
                    "QSL-409-QCR-0002", "QSO已存在卡片：" + conflict.get()));
            }
            return Flux.fromIterable(names)
                .concatMap(name -> client.fetch(QslCardRequestQsoReservation.class, reservationName(name))
                    .flatMap(reservation -> Mono.error(new QslApiException(HttpStatus.CONFLICT,
                        "QSL-409-QCR-0001", "QSO存在待处理申请：" + name)))
                    .switchIfEmpty(Mono.empty()))
                .then();
        });
    }

    private Mono<List<ValidatedQsoItem>> validateCardVersions(List<ValidatedQsoItem> requestedItems) {
        return Mono.zip(
            client.listAll(StationCard.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(item -> item.getSpec() != null && item.getMetadata() != null)
                .collectList(),
            loadCardVersionUsedCounts()
        ).map(tuple -> {
            Map<String, StationCard> cards = new LinkedHashMap<>();
            for (var stationCard : tuple.getT1()) {
                var version = normalize(stationCard.getSpec().getCardVersion());
                if (!version.isBlank()) {
                    cards.putIfAbsent(version.toUpperCase(Locale.ROOT), stationCard);
                }
            }
            Map<String, Integer> requestedCounts = new LinkedHashMap<>();
            var canonicalItems = new ArrayList<ValidatedQsoItem>();
            for (var item : requestedItems) {
                var key = normalize(item.cardVersion()).toUpperCase(Locale.ROOT);
                var stationCard = cards.get(key);
                if (stationCard == null) {
                    throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-QCR-0001", "卡片版本不存在：" + item.cardVersion());
                }
                var canonicalVersion = normalize(stationCard.getSpec().getCardVersion());
                requestedCounts.merge(key, 1, Integer::sum);
                canonicalItems.add(new ValidatedQsoItem(item.qsoRecordName(), canonicalVersion));
            }
            var usedCounts = tuple.getT2();
            for (var entry : requestedCounts.entrySet()) {
                var stationCard = cards.get(entry.getKey());
                var total = safeInventory(stationCard.getSpec().getAvailableInventory());
                var remaining = Math.max(total - usedCounts.getOrDefault(entry.getKey(), 0), 0);
                if (entry.getValue() > remaining) {
                    throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-QCR-0001", "卡片版本库存不足：" + stationCard.getSpec().getCardVersion());
                }
            }
            return List.copyOf(canonicalItems);
        });
    }

    private Mono<AddressBinding> resolveAddress(SubmitCommand command) {
        if ("BUREAU".equals(command.addressType())) {
            return client.fetch(BureauEntry.class, command.bureauId())
                .filter(item -> item.getMetadata() != null && item.getSpec() != null)
                .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "QSL-422-QCR-0002", "所选卡片局不存在")))
                .map(item -> new AddressBinding(item.getMetadata().getName()));
        }
        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(item -> item.getMetadata() != null && item.getSpec() != null)
            .filter(item -> command.callSign().equals(QslApiSupport.normalizeCallSign(item.getSpec().getCallSign())))
            .filter(item -> command.name().equals(normalize(item.getSpec().getName())))
            .filter(item -> command.telephone().equals(normalize(item.getSpec().getTelephone())))
            .filter(item -> command.postalCode().equals(normalize(item.getSpec().getPostalCode())))
            .filter(item -> command.address().equals(normalize(item.getSpec().getAddress())))
            .filter(item -> command.notificationEmail().equals(normalize(item.getSpec().getEmail())))
            .next()
            .map(item -> new AddressBinding(item.getMetadata().getName()))
            .switchIfEmpty(createPersonalAddress(command));
    }

    private Mono<AddressBinding> createPersonalAddress(SubmitCommand command) {
        return nextAddressName(command.callSign()).flatMap(name -> {
            var entry = new AddressBookEntry();
            entry.setMetadata(QslApiSupport.createMetadata(name));
            var spec = new AddressBookEntry.AddressBookSpec();
            spec.setCallSign(command.callSign());
            spec.setName(command.name());
            spec.setTelephone(command.telephone());
            spec.setPostalCode(command.postalCode());
            spec.setDestinationCountry("");
            spec.setAddress(command.address());
            spec.setEmail(command.notificationEmail());
            spec.setAddressRemarks("实体QSL卡申请自动生成");
            entry.setSpec(spec);
            var status = new AddressBookEntry.AddressBookStatus();
            status.setSyncStatus("QSL_CARD_REQUEST_AUTO");
            entry.setStatus(status);
            return client.create(entry).map(created -> new AddressBinding(created.getMetadata().getName()));
        });
    }

    private Mono<List<QslCardRequestQsoReservation>> acquireReservations(
        String requestName,
        List<ValidatedQsoItem> items
    ) {
        var acquired = new ArrayList<QslCardRequestQsoReservation>();
        return acquireReservationAt(requestName, items, 0, acquired)
            .onErrorResume(error -> releaseReservations(acquired).then(Mono.error(mapReservationError(error))));
    }

    private Mono<List<QslCardRequestQsoReservation>> acquireReservationAt(
        String requestName,
        List<ValidatedQsoItem> items,
        int index,
        List<QslCardRequestQsoReservation> acquired
    ) {
        if (index >= items.size()) {
            return Mono.just(List.copyOf(acquired));
        }
        var qsoName = items.get(index).qsoRecordName();
        var reservation = new QslCardRequestQsoReservation();
        reservation.setMetadata(QslApiSupport.createMetadata(reservationName(qsoName)));
        var spec = new QslCardRequestQsoReservation.QslCardRequestQsoReservationSpec();
        spec.setRequestName(requestName);
        spec.setQsoRecordName(qsoName);
        spec.setCreatedAt(QslApiSupport.nowText());
        reservation.setSpec(spec);
        var status = new QslCardRequestQsoReservation.QslCardRequestQsoReservationStatus();
        status.setState("ACTIVE");
        reservation.setStatus(status);
        return client.create(reservation)
            .doOnNext(acquired::add)
            .then(acquireReservationAt(requestName, items, index + 1, acquired));
    }

    private Mono<Void> releaseReservations(List<QslCardRequestQsoReservation> reservations) {
        return Flux.fromIterable(reservations)
            .concatMap(item -> client.delete(item).onErrorResume(error -> Mono.empty()))
            .then();
    }

    private Mono<Void> releaseReservationsForRequest(String requestName) {
        return client.listAll(QslCardRequestQsoReservation.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(item -> item.getSpec() != null)
            .filter(item -> requestName.equals(normalize(item.getSpec().getRequestName())))
            .concatMap(item -> client.delete(item).onErrorResume(error -> Mono.empty()))
            .then();
    }

    private Mono<Void> releaseReservationForQso(String qsoRecordName) {
        return client.fetch(QslCardRequestQsoReservation.class, reservationName(qsoRecordName))
            .flatMap(client::delete)
            .onErrorResume(error -> Mono.empty())
            .then();
    }

    private Mono<QslCardRequest> markApprovedAndCreating(
        QslCardRequest request,
        String reason,
        String operator
    ) {
        var status = ensureStatus(request);
        status.setReviewStatus(REVIEW_APPROVED);
        status.setReviewReason(normalize(reason));
        status.setReviewedBy(safeOperator(operator));
        status.setReviewedAt(QslApiSupport.nowText());
        status.setCardCreationStatus(CARD_STATUS_CREATING);
        request.setStatus(status);
        return client.update(request);
    }

    private Mono<QslCardRequest> createRemainingCards(QslCardRequest request, String operator, String clientIp) {
        var items = safeQsoItems(request);
        return createCardAt(request, items, 0, operator, clientIp)
            .flatMap(updated -> {
                var status = ensureStatus(updated);
                var successCount = safeCreatedCards(status).stream()
                    .filter(item -> "成功".equals(normalize(item.getCreationStatus())))
                    .map(QslCardRequest.CreatedCardItem::getQsoRecordName)
                    .distinct()
                    .count();
                status.setCardCreationStatus(successCount == items.size()
                    ? CARD_STATUS_SUCCESS : CARD_STATUS_PARTIAL);
                updated.setStatus(status);
                return client.update(updated);
            })
            .flatMap(updated -> CARD_STATUS_SUCCESS.equals(updated.getStatus().getCardCreationStatus())
                ? releaseReservationsForRequest(updated.getMetadata().getName()).thenReturn(updated)
                : Mono.just(updated));
    }

    private Mono<QslCardRequest> createCardAt(
        QslCardRequest request,
        List<QslCardRequest.QsoItem> items,
        int index,
        String operator,
        String clientIp
    ) {
        if (index >= items.size()) {
            return Mono.just(request);
        }
        var item = items.get(index);
        var existingResult = findCreatedResult(request, item.getQsoRecordName());
        if (existingResult != null && "成功".equals(normalize(existingResult.getCreationStatus()))) {
            return createCardAt(request, items, index + 1, operator, clientIp);
        }
        return consoleActionService.createCardForQslCardRequest(
                request.getMetadata().getName(),
                request.getSpec().getCallSign(),
                item.getQsoRecordName(),
                item.getCardVersion(),
                request.getSpec().getAddressEntryName(),
                request.getSpec().getNotificationEmail(),
                request.getSpec().getRemarks()
            )
            .flatMap(card -> notificationMailService.autoSendIfEnabled(
                    card.getMetadata().getName(),
                    QslNotificationMailService.MailScene.CARD_CREATED,
                    operator,
                    clientIp
                ).onErrorResume(error -> Mono.empty()).thenReturn(card))
            .flatMap(card -> updateCreatedResult(request, item, card.getMetadata().getName(), "成功", "")
                .flatMap(updated -> releaseReservationForQso(item.getQsoRecordName()).thenReturn(updated)))
            .onErrorResume(error -> updateCreatedResult(
                request, item, "", "失败", shortError(error)))
            .flatMap(updated -> createCardAt(updated, items, index + 1, operator, clientIp));
    }

    private Mono<QslCardRequest> updateCreatedResult(
        QslCardRequest request,
        QslCardRequest.QsoItem qsoItem,
        String cardRecordName,
        String creationStatus,
        String lastError
    ) {
        var status = ensureStatus(request);
        var createdCards = new ArrayList<>(safeCreatedCards(status));
        createdCards.removeIf(item -> normalize(qsoItem.getQsoRecordName())
            .equals(normalize(item.getQsoRecordName())));
        var result = new QslCardRequest.CreatedCardItem();
        result.setQsoRecordName(normalize(qsoItem.getQsoRecordName()));
        result.setCardVersion(normalize(qsoItem.getCardVersion()));
        result.setCardRecordName(normalize(cardRecordName));
        result.setCreationStatus(creationStatus);
        result.setLastError(normalize(lastError));
        createdCards.add(result);
        status.setCreatedCards(createdCards);
        request.setStatus(status);
        return client.update(request);
    }

    private Mono<Void> validateRequestBeforeApproval(QslCardRequest request) {
        var items = safeQsoItems(request);
        if (items.isEmpty()) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-QCR-0003", "申请没有可创建的QSO项目"));
        }
        return validateRequestQsoOwnership(request)
            .then(validateNoForeignCards(request))
            .then(validateCardVersions(items.stream()
                .map(item -> new ValidatedQsoItem(normalize(item.getQsoRecordName()), normalize(item.getCardVersion())))
                .toList()).then());
    }

    private Mono<Void> validateRequestBeforeRetry(QslCardRequest request) {
        var pendingItems = safeQsoItems(request).stream()
            .filter(item -> {
                var existing = findCreatedResult(request, item.getQsoRecordName());
                return existing == null || !"成功".equals(normalize(existing.getCreationStatus()));
            })
            .map(item -> new ValidatedQsoItem(
                normalize(item.getQsoRecordName()), normalize(item.getCardVersion())
            ))
            .toList();
        return validateRequestQsoOwnership(request)
            .then(validateNoForeignCards(request))
            .then(pendingItems.isEmpty() ? Mono.empty() : validateCardVersions(pendingItems).then());
    }

    private boolean shouldReleaseReservation(
        QslCardRequestQsoReservation reservation,
        Map<String, QslCardRequest> requests,
        Set<String> occupiedQsoNames
    ) {
        if (reservation.getMetadata() == null || reservation.getSpec() == null) {
            return true;
        }
        var requestName = normalize(reservation.getSpec().getRequestName());
        var qsoRecordName = normalize(reservation.getSpec().getQsoRecordName());
        if (requestName.isBlank() || qsoRecordName.isBlank()) {
            return true;
        }
        var request = requests.get(requestName);
        if (request == null || safeQsoItems(request).stream()
            .noneMatch(item -> qsoRecordName.equals(normalize(item.getQsoRecordName())))) {
            return true;
        }
        var reviewStatus = normalize(ensureStatus(request).getReviewStatus());
        if (REVIEW_REJECTED.equals(reviewStatus)) {
            return true;
        }
        return REVIEW_APPROVED.equals(reviewStatus) && occupiedQsoNames.contains(qsoRecordName);
    }

    private Mono<Void> validateRequestQsoOwnership(QslCardRequest request) {
        var callSign = QslApiSupport.normalizeCallSign(request.getSpec().getCallSign());
        return Flux.fromIterable(safeQsoItems(request))
            .concatMap(item -> client.fetch(QsoRecord.class, normalize(item.getQsoRecordName()))
                .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "QSL-422-QCR-0003", "QSO记录不存在：" + item.getQsoRecordName())))
                .flatMap(qso -> {
                    if (qso.getSpec() == null
                        || !callSign.equals(QslApiSupport.normalizeCallSign(qso.getSpec().getCallSign()))
                        || !"QSO".equals(normalizeQsoSceneType(qso.getSpec().getSceneType()))) {
                        return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "QSL-422-QCR-0003", "QSO记录与申请不一致：" + item.getQsoRecordName()));
                    }
                    return Mono.empty();
                }))
            .then();
    }

    private Mono<Void> validateNoForeignCards(QslCardRequest request) {
        var requestName = request.getMetadata().getName();
        var names = safeQsoItems(request).stream()
            .map(item -> normalize(item.getQsoRecordName()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(card -> card.getSpec() != null)
            .filter(card -> names.contains(normalize(card.getSpec().getQsoRecordName())))
            .filter(card -> !normalize(card.getSpec().getBusinessRemarks()).contains("申请编号：" + requestName))
            .next()
            .flatMap(card -> Mono.error(new QslApiException(HttpStatus.CONFLICT,
                "QSL-409-QCR-0002", "QSO已存在其他卡片：" + card.getSpec().getQsoRecordName())))
            .then();
    }

    private Mono<Set<String>> occupiedQsoRecordNames() {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(item -> item.getSpec() != null)
            .map(item -> normalize(item.getSpec().getQsoRecordName()))
            .filter(item -> !item.isBlank())
            .collect(LinkedHashSet::new, Set::add);
    }

    private Mono<Map<String, Integer>> loadCardVersionUsedCounts() {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(item -> item.getSpec() != null)
            .map(item -> normalize(item.getSpec().getCardVersion()))
            .collectList()
            .map(versions -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (var value : versions) {
                    for (var version : value.split("[、,]")) {
                        var key = normalize(version).toUpperCase(Locale.ROOT);
                        if (!key.isBlank()) {
                            counts.merge(key, 1, Integer::sum);
                        }
                    }
                }
                return counts;
            });
    }

    private Mono<String> nextRequestName() {
        return client.listAll(QslCardRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(item -> item.getMetadata() == null ? "" : normalize(item.getMetadata().getName()))
            .map(this::requestSequence)
            .reduce(0, Math::max)
            .map(next -> "QCR" + String.format(Locale.ROOT, "%04d", next + 1));
    }

    private Mono<String> nextAddressName(String callSign) {
        var prefix = callSign + "-";
        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(item -> item.getMetadata() == null ? "" : normalize(item.getMetadata().getName()))
            .filter(name -> name.startsWith(prefix))
            .map(name -> {
                try {
                    return Integer.parseInt(name.substring(prefix.length()));
                } catch (NumberFormatException error) {
                    return 0;
                }
            })
            .reduce(0, Math::max)
            .map(next -> prefix + (next + 1));
    }

    private int requestSequence(String name) {
        var matcher = REQUEST_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private String reservationName(String qsoRecordName) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(normalize(qsoRecordName).getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder("qcr-qso-");
            for (var index = 0; index < 12; index++) {
                builder.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境不支持SHA-256", error);
        }
    }

    private SubmitCommand normalizeAndValidateCommand(SubmitCommand command) {
        if (command == null) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "申请内容不能为空");
        }
        var callSign = normalizeAndValidateCallSign(command.callSign());
        var addressType = normalize(command.addressType()).toUpperCase(Locale.ROOT);
        if (!Set.of("PERSONAL", "BUREAU").contains(addressType)) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "地址类型不合法");
        }
        var email = normalize(command.notificationEmail());
        if (email.isBlank() || email.length() > 120 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "通知电子邮箱格式不合法");
        }
        var rawItems = command.qsoItems() == null ? List.<RequestedQsoItem>of() : command.qsoItems();
        if (rawItems.isEmpty() || rawItems.size() > MAX_QSO_ITEMS) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001",
                "每次必须选择1至" + MAX_QSO_ITEMS + "条QSO记录");
        }
        var normalizedItems = new ArrayList<RequestedQsoItem>();
        var names = new LinkedHashSet<String>();
        for (var item : rawItems) {
            var qsoName = item == null ? "" : normalize(item.qsoRecordName());
            var cardVersion = item == null ? "" : normalize(item.cardVersion());
            if (qsoName.isBlank() || cardVersion.isBlank()) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001",
                    "每条QSO都必须选择卡片版本");
            }
            if (!names.add(qsoName)) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "QSO记录不能重复");
            }
            normalizedItems.add(new RequestedQsoItem(qsoName, cardVersion));
        }
        var name = normalize(command.name());
        var telephone = normalize(command.telephone());
        var postalCode = normalize(command.postalCode());
        var address = normalize(command.address());
        var bureauId = normalize(command.bureauId());
        if ("PERSONAL".equals(addressType)) {
            if (name.length() > 60) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "收件人姓名不能超过60字符");
            }
            if (!telephone.isBlank() && !TELEPHONE_PATTERN.matcher(telephone).matches()) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "联系电话格式不合法");
            }
            if (!POSTAL_CODE_PATTERN.matcher(postalCode).matches()) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "邮政编码不能为空或格式不合法");
            }
            if (address.isBlank() || address.length() > 200) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "通信地址不能为空或过长");
            }
            bureauId = "";
        } else {
            if (bureauId.isBlank() || bureauId.length() > 128) {
                throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "请选择现有卡片局");
            }
            name = "";
            telephone = "";
            postalCode = "";
            address = "";
        }
        var remarks = normalize(command.remarks());
        if (remarks.length() > 500) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "备注长度不能超过500字符");
        }
        return new SubmitCommand(
            callSign, List.copyOf(normalizedItems), addressType, name, telephone,
            postalCode, address, bureauId, email, remarks
        );
    }

    private String normalizeAndValidateCallSign(String rawCallSign) {
        var callSign = QslApiSupport.normalizeCallSign(rawCallSign);
        if (!CALL_SIGN_PATTERN.matcher(callSign).matches()) {
            throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-QCR-0001", "呼号格式不合法");
        }
        return callSign;
    }

    private Mono<QslCardRequest> fetchRequest(String requestName) {
        return client.fetch(QslCardRequest.class, normalize(requestName))
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND,
                "QSL-404-QCR-0001", "实体卡申请不存在")));
    }

    private Mono<Void> ensurePending(QslCardRequest request) {
        if (!REVIEW_PENDING.equals(normalize(ensureStatus(request).getReviewStatus()))) {
            return Mono.error(new QslApiException(HttpStatus.CONFLICT,
                "QSL-409-QCR-0003", "申请已经处理，不能重复审核"));
        }
        return Mono.empty();
    }

    private QslCardRequest.QslCardRequestStatus ensureStatus(QslCardRequest request) {
        if (request.getStatus() == null) {
            request.setStatus(new QslCardRequest.QslCardRequestStatus());
        }
        if (request.getStatus().getCreatedCards() == null) {
            request.getStatus().setCreatedCards(new ArrayList<>());
        }
        return request.getStatus();
    }

    private List<QslCardRequest.QsoItem> safeQsoItems(QslCardRequest request) {
        return request.getSpec() == null || request.getSpec().getQsoItems() == null
            ? List.of() : request.getSpec().getQsoItems();
    }

    private List<QslCardRequest.CreatedCardItem> safeCreatedCards(QslCardRequest.QslCardRequestStatus status) {
        return status == null || status.getCreatedCards() == null ? List.of() : status.getCreatedCards();
    }

    private QslCardRequest.CreatedCardItem findCreatedResult(QslCardRequest request, String qsoRecordName) {
        return safeCreatedCards(ensureStatus(request)).stream()
            .filter(item -> normalize(qsoRecordName).equals(normalize(item.getQsoRecordName())))
            .findFirst()
            .orElse(null);
    }

    private ReviewResult toReviewResult(QslCardRequest request) {
        var status = ensureStatus(request);
        return new ReviewResult(
            request.getMetadata().getName(),
            status.getReviewStatus(),
            status.getCardCreationStatus(),
            List.copyOf(safeCreatedCards(status)),
            status.getReviewReason()
        );
    }

    private <T> Mono<T> withSubmissionLock(java.util.function.Supplier<Mono<T>> action) {
        return Mono.defer(() -> {
            if (!submissionLock.compareAndSet(false, true)) {
                return Mono.error(new QslApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "QSL-429-QCR-0001", "实体卡申请正在提交处理中，请稍后再试"));
            }
            try {
                return action.get().doFinally(signal -> submissionLock.set(false));
            } catch (Throwable error) {
                submissionLock.set(false);
                return Mono.error(error);
            }
        });
    }

    private Throwable mapReservationError(Throwable error) {
        if (error instanceof QslApiException) {
            return error;
        }
        if (error != null && error.getMessage() != null
            && error.getMessage().toLowerCase(Locale.ROOT).contains("duplicate")) {
            return new QslApiException(HttpStatus.CONFLICT,
                "QSL-409-QCR-0001", "所选QSO存在正在处理的申请");
        }
        return error;
    }

    private int safeInventory(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private String normalizeQsoSceneType(String value) {
        var normalized = normalize(value).toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "QSO" : normalized;
    }

    private String safeOperator(String operator) {
        return normalize(operator).isBlank() ? "qsl-system" : normalize(operator);
    }

    private String shortError(Throwable error) {
        var message = error == null ? "未知错误" : normalize(error.getMessage());
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record PublicQsoEligibilityResult(
        String callSign,
        List<PublicQsoEligibilityItem> items,
        int total
    ) {
    }

    public record PublicQsoEligibilityItem(
        String qsoRecordName,
        String date,
        String time,
        String freq,
        String qth,
        boolean selectable,
        String unselectableReason
    ) {
    }

    public record PublicStationContact(String stationEmail) {
    }

    public record ReservationReconcileResult(int scanned, int released, int kept) {
    }

    public record RequestedQsoItem(String qsoRecordName, String cardVersion) {
    }

    public record SubmitCommand(
        String callSign,
        List<RequestedQsoItem> qsoItems,
        String addressType,
        String name,
        String telephone,
        String postalCode,
        String address,
        String bureauId,
        String notificationEmail,
        String remarks
    ) {
    }

    public record SubmitResult(
        String requestName,
        String callSign,
        String reviewStatus,
        String submittedAt,
        int qsoCount
    ) {
    }

    public record ReviewResult(
        String requestName,
        String reviewStatus,
        String cardCreationStatus,
        List<QslCardRequest.CreatedCardItem> createdCards,
        String reviewReason
    ) {
    }

    private record ValidatedQsoItem(String qsoRecordName, String cardVersion) {
    }

    private record AddressBinding(String resourceName) {
    }
}
