package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import com.novel.agent.exception.CostLimitExceededException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCostService {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private final AiProperties aiProperties;
    private final Deque<UsageRecord> records = new ArrayDeque<>();
    private final Deque<DegradationEvent> degradationEvents = new ArrayDeque<>();

    public UsageReservation reserveChatRequest(String provider, String model, String source,
                                               String promptText, int reservedCompletionTokens) {
        return reserveChatRequest(null, provider, model, source, promptText, reservedCompletionTokens);
    }

    public UsageReservation reserveChatRequest(Long novelId, String provider, String model, String source,
                                               String promptText, int reservedCompletionTokens) {
        int inputTokens = estimateTokens(promptText);
        int outputTokens = Math.max(reservedCompletionTokens, 0);
        int charCount = promptText == null ? 0 : promptText.length();
        return reserveUsage(novelId, UsageType.CHAT, provider, model, source, inputTokens, outputTokens, 0, charCount);
    }

    public UsageReservation reserveEmbeddingRequest(String provider, String model, String source, List<String> texts) {
        return reserveEmbeddingRequest(null, provider, model, source, texts);
    }

    public UsageReservation reserveEmbeddingRequest(Long novelId, String provider, String model, String source, List<String> texts) {
        int embeddingTokens = estimateTokens(texts);
        int charCount = texts == null ? 0 : texts.stream().filter(Objects::nonNull).mapToInt(String::length).sum();
        return reserveUsage(novelId, UsageType.EMBEDDING, provider, model, source, 0, 0, embeddingTokens, charCount);
    }

    public void recordChatSuccess(UsageReservation reservation, String completionText,
                                  Integer actualInputTokens, Integer actualOutputTokens) {
        int inputTokens = actualInputTokens != null ? actualInputTokens : reservation.getEstimatedInputTokens();
        int outputTokens = actualOutputTokens != null ? actualOutputTokens : estimateTokens(completionText);
        appendRecord(buildRecord(reservation, UsageStatus.SUCCESS, inputTokens, outputTokens, 0, null));
    }

    public void recordEmbeddingSuccess(UsageReservation reservation, Integer actualEmbeddingTokens) {
        int embeddingTokens = actualEmbeddingTokens != null ? actualEmbeddingTokens : reservation.getEstimatedEmbeddingTokens();
        appendRecord(buildRecord(reservation, UsageStatus.SUCCESS, 0, 0, embeddingTokens, null));
    }

    public void recordFailure(UsageReservation reservation, String errorMessage) {
        appendRecord(buildRecord(
                reservation,
                UsageStatus.FAILED,
                reservation.getEstimatedInputTokens(),
                0,
                reservation.getEstimatedEmbeddingTokens(),
                trimMessage(errorMessage)
        ));
    }

    public synchronized void recordDegradation(String trigger,
                                               String strategy,
                                               String detail,
                                               Long novelId,
                                               String provider,
                                               String model,
                                               String source) {
        DegradationEvent event = DegradationEvent.builder()
                .timestamp(System.currentTimeMillis())
                .trigger(trigger)
                .strategy(strategy)
                .detail(trimMessage(detail))
                .novelId(novelId)
                .provider(provider)
                .model(model)
                .source(source)
                .build();
        degradationEvents.addFirst(event);
        trimDegradationEvents();
    }

    public synchronized List<DegradationEvent> getRecentDegradations(int limit) {
        return degradationEvents.stream()
                .sorted(Comparator.comparingLong(DegradationEvent::getTimestamp).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    public synchronized DashboardSummary getDashboardSummary() {
        List<UsageRecord> snapshot = new ArrayList<>(records);
        UsageWindow today = aggregate(filterByToday(snapshot));
        UsageWindow month = aggregate(filterByMonth(snapshot));
        UsageWindow total = aggregate(snapshot);
        return DashboardSummary.builder()
                .settings(toSettingsSnapshot())
                .today(today)
                .month(month)
                .total(total)
                .dailyTrend(buildDailyTrend(snapshot, 7))
                .byModel(buildScopedSummaries(snapshot, ScopeDimension.MODEL))
                .byNovel(buildScopedSummaries(snapshot, ScopeDimension.NOVEL))
                .recentDegradations(getRecentDegradations(10))
                .build();
    }

    public synchronized List<UsageRecord> getRecentRecords(int limit) {
        return records.stream()
                .sorted(Comparator.comparingLong(UsageRecord::getTimestamp).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    public SettingsSnapshot getSettingsSnapshot() {
        return toSettingsSnapshot();
    }

    public synchronized SettingsSnapshot updateSettings(SettingsUpdateRequest request) {
        AiProperties.CostControl settings = aiProperties.getCostControl();
        if (request.getEnabled() != null) settings.setEnabled(request.getEnabled());
        if (request.getStrictMode() != null) settings.setStrictMode(request.getStrictMode());
        if (request.getRecentRecords() != null) settings.setRecentRecords(Math.max(20, request.getRecentRecords()));
        if (request.getMaxEstimatedTokensPerRequest() != null) settings.setMaxEstimatedTokensPerRequest(Math.max(500, request.getMaxEstimatedTokensPerRequest()));
        if (request.getReservedCompletionTokens() != null) settings.setReservedCompletionTokens(Math.max(0, request.getReservedCompletionTokens()));
        if (request.getDailyTokenBudget() != null) settings.setDailyTokenBudget(Math.max(0, request.getDailyTokenBudget()));
        if (request.getMonthlyTokenBudget() != null) settings.setMonthlyTokenBudget(Math.max(0, request.getMonthlyTokenBudget()));
        if (request.getDailyBudgetUsd() != null) settings.setDailyBudgetUsd(Math.max(0, request.getDailyBudgetUsd()));
        if (request.getMonthlyBudgetUsd() != null) settings.setMonthlyBudgetUsd(Math.max(0, request.getMonthlyBudgetUsd()));
        if (request.getPerNovelDailyTokenBudget() != null) settings.setPerNovelDailyTokenBudget(Math.max(0, request.getPerNovelDailyTokenBudget()));
        if (request.getPerNovelDailyBudgetUsd() != null) settings.setPerNovelDailyBudgetUsd(Math.max(0, request.getPerNovelDailyBudgetUsd()));
        if (request.getPerModelDailyTokenBudget() != null) settings.setPerModelDailyTokenBudget(Math.max(0, request.getPerModelDailyTokenBudget()));
        if (request.getPerModelDailyBudgetUsd() != null) settings.setPerModelDailyBudgetUsd(Math.max(0, request.getPerModelDailyBudgetUsd()));
        if (request.getDegradeOnBudgetExceeded() != null) settings.setDegradeOnBudgetExceeded(request.getDegradeOnBudgetExceeded());
        if (request.getDegradeOnModelFailure() != null) settings.setDegradeOnModelFailure(request.getDegradeOnModelFailure());
        if (request.getDegradeOnEmbeddingFailure() != null) settings.setDegradeOnEmbeddingFailure(request.getDegradeOnEmbeddingFailure());
        if (request.getPricing() != null) {
            AiProperties.Pricing pricing = settings.getPricing();
            if (request.getPricing().getCurrency() != null) pricing.setCurrency(request.getPricing().getCurrency());
            if (request.getPricing().getInputPerMillionTokens() != null) pricing.setInputPerMillionTokens(Math.max(0, request.getPricing().getInputPerMillionTokens()));
            if (request.getPricing().getOutputPerMillionTokens() != null) pricing.setOutputPerMillionTokens(Math.max(0, request.getPricing().getOutputPerMillionTokens()));
            if (request.getPricing().getEmbeddingPerMillionTokens() != null) pricing.setEmbeddingPerMillionTokens(Math.max(0, request.getPricing().getEmbeddingPerMillionTokens()));
        }
        trimToRecentRecordLimit();
        trimDegradationEvents();
        return toSettingsSnapshot();
    }

    public synchronized void clearRecords() {
        records.clear();
        degradationEvents.clear();
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int cjkChars = 0;
        int latinChars = 0;
        int otherChars = 0;

        for (char ch : text.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjkChars++;
            } else if (Character.isLetterOrDigit(ch)) {
                latinChars++;
            } else {
                otherChars++;
            }
        }

        return cjkChars + (int) Math.ceil(latinChars / 4.0) + (int) Math.ceil(otherChars / 2.0);
    }

    public int estimateTokens(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        return texts.stream().filter(Objects::nonNull).mapToInt(this::estimateTokens).sum();
    }

    private synchronized UsageReservation reserveUsage(Long novelId,
                                                       UsageType usageType,
                                                       String provider,
                                                       String model,
                                                       String source,
                                                       int estimatedInputTokens,
                                                       int estimatedOutputTokens,
                                                       int estimatedEmbeddingTokens,
                                                       int charCount) {
        UsageReservation reservation = UsageReservation.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(System.currentTimeMillis())
                .novelId(novelId)
                .usageType(usageType)
                .provider(provider)
                .model(model)
                .source(source)
                .estimatedInputTokens(estimatedInputTokens)
                .estimatedOutputTokens(estimatedOutputTokens)
                .estimatedEmbeddingTokens(estimatedEmbeddingTokens)
                .charCount(charCount)
                .build();

        AiProperties.CostControl settings = aiProperties.getCostControl();
        if (!settings.isEnabled()) {
            return reservation;
        }

        int estimatedTotalTokens = estimatedInputTokens + estimatedOutputTokens + estimatedEmbeddingTokens;
        double estimatedCostUsd = calculateCostUsd(estimatedInputTokens, estimatedOutputTokens, estimatedEmbeddingTokens);
        reservation.setEstimatedCostUsd(estimatedCostUsd);

        if (settings.isStrictMode()) {
            String reason = checkLimitReason(novelId, provider, model, estimatedTotalTokens, estimatedCostUsd, settings);
            if (reason != null) {
                appendRecord(buildRecord(reservation, UsageStatus.BLOCKED, estimatedInputTokens, estimatedOutputTokens, estimatedEmbeddingTokens, reason));
                throw new CostLimitExceededException(reason);
            }
        }
        return reservation;
    }

    private String checkLimitReason(Long novelId,
                                    String provider,
                                    String model,
                                    int estimatedTotalTokens,
                                    double estimatedCostUsd,
                                    AiProperties.CostControl settings) {
        if (settings.getMaxEstimatedTokensPerRequest() > 0 && estimatedTotalTokens > settings.getMaxEstimatedTokensPerRequest()) {
            return "estimated token limit exceeded: " + estimatedTotalTokens + " > " + settings.getMaxEstimatedTokensPerRequest();
        }

        List<UsageRecord> snapshot = new ArrayList<>(records);
        UsageWindow today = aggregate(filterByToday(snapshot));
        UsageWindow month = aggregate(filterByMonth(snapshot));

        if (settings.getDailyTokenBudget() > 0 && today.getBillableTokens() + estimatedTotalTokens > settings.getDailyTokenBudget()) {
            return "daily token budget exceeded";
        }
        if (settings.getMonthlyTokenBudget() > 0 && month.getBillableTokens() + estimatedTotalTokens > settings.getMonthlyTokenBudget()) {
            return "monthly token budget exceeded";
        }
        if (settings.getDailyBudgetUsd() > 0 && today.getEstimatedCostUsd() + estimatedCostUsd > settings.getDailyBudgetUsd()) {
            return "daily cost budget exceeded";
        }
        if (settings.getMonthlyBudgetUsd() > 0 && month.getEstimatedCostUsd() + estimatedCostUsd > settings.getMonthlyBudgetUsd()) {
            return "monthly cost budget exceeded";
        }

        if (novelId != null) {
            UsageWindow novelToday = aggregate(filterByNovelAndToday(snapshot, novelId));
            if (settings.getPerNovelDailyTokenBudget() > 0 && novelToday.getBillableTokens() + estimatedTotalTokens > settings.getPerNovelDailyTokenBudget()) {
                return "per-novel daily token budget exceeded: novelId=" + novelId;
            }
            if (settings.getPerNovelDailyBudgetUsd() > 0 && novelToday.getEstimatedCostUsd() + estimatedCostUsd > settings.getPerNovelDailyBudgetUsd()) {
                return "per-novel daily cost budget exceeded: novelId=" + novelId;
            }
        }

        UsageWindow modelToday = aggregate(filterByModelAndToday(snapshot, provider, model));
        if (settings.getPerModelDailyTokenBudget() > 0 && modelToday.getBillableTokens() + estimatedTotalTokens > settings.getPerModelDailyTokenBudget()) {
            return "per-model daily token budget exceeded: " + buildModelKey(provider, model);
        }
        if (settings.getPerModelDailyBudgetUsd() > 0 && modelToday.getEstimatedCostUsd() + estimatedCostUsd > settings.getPerModelDailyBudgetUsd()) {
            return "per-model daily cost budget exceeded: " + buildModelKey(provider, model);
        }
        return null;
    }

    private UsageRecord buildRecord(UsageReservation reservation,
                                    UsageStatus status,
                                    int inputTokens,
                                    int outputTokens,
                                    int embeddingTokens,
                                    String note) {
        int totalTokens = inputTokens + outputTokens + embeddingTokens;
        return UsageRecord.builder()
                .requestId(reservation.getRequestId())
                .timestamp(System.currentTimeMillis())
                .novelId(reservation.getNovelId())
                .usageType(reservation.getUsageType().name())
                .status(status.name())
                .provider(reservation.getProvider())
                .model(reservation.getModel())
                .source(reservation.getSource())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .embeddingTokens(embeddingTokens)
                .totalTokens(totalTokens)
                .estimatedCostUsd(round(calculateCostUsd(inputTokens, outputTokens, embeddingTokens)))
                .charCount(reservation.getCharCount())
                .note(note)
                .build();
    }

    private synchronized void appendRecord(UsageRecord record) {
        records.addFirst(record);
        trimToRecentRecordLimit();
    }

    private void trimToRecentRecordLimit() {
        int limit = Math.max(20, aiProperties.getCostControl().getRecentRecords());
        while (records.size() > limit) {
            records.removeLast();
        }
    }

    private void trimDegradationEvents() {
        int limit = Math.max(20, aiProperties.getCostControl().getRecentRecords());
        while (degradationEvents.size() > limit) {
            degradationEvents.removeLast();
        }
    }

    private List<UsageRecord> filterByToday(List<UsageRecord> snapshot) {
        LocalDate today = LocalDate.now(ZONE_ID);
        return snapshot.stream()
                .filter(record -> Instant.ofEpochMilli(record.getTimestamp()).atZone(ZONE_ID).toLocalDate().equals(today))
                .collect(Collectors.toList());
    }

    private List<UsageRecord> filterByMonth(List<UsageRecord> snapshot) {
        YearMonth currentMonth = YearMonth.now(ZONE_ID);
        return snapshot.stream()
                .filter(record -> YearMonth.from(Instant.ofEpochMilli(record.getTimestamp()).atZone(ZONE_ID)).equals(currentMonth))
                .collect(Collectors.toList());
    }

    private List<UsageRecord> filterByNovelAndToday(List<UsageRecord> snapshot, Long novelId) {
        LocalDate today = LocalDate.now(ZONE_ID);
        return snapshot.stream()
                .filter(record -> Objects.equals(record.getNovelId(), novelId))
                .filter(record -> Instant.ofEpochMilli(record.getTimestamp()).atZone(ZONE_ID).toLocalDate().equals(today))
                .collect(Collectors.toList());
    }

    private List<UsageRecord> filterByModelAndToday(List<UsageRecord> snapshot, String provider, String model) {
        LocalDate today = LocalDate.now(ZONE_ID);
        return snapshot.stream()
                .filter(record -> Objects.equals(normalize(record.getProvider()), normalize(provider)))
                .filter(record -> Objects.equals(normalize(record.getModel()), normalize(model)))
                .filter(record -> Instant.ofEpochMilli(record.getTimestamp()).atZone(ZONE_ID).toLocalDate().equals(today))
                .collect(Collectors.toList());
    }

    private UsageWindow aggregate(List<UsageRecord> scopedRecords) {
        UsageWindow window = new UsageWindow();
        for (UsageRecord record : scopedRecords) {
            window.requestCount++;
            switch (record.getStatus()) {
                case "SUCCESS" -> {
                    window.successCount++;
                    window.inputTokens += record.getInputTokens();
                    window.outputTokens += record.getOutputTokens();
                    window.embeddingTokens += record.getEmbeddingTokens();
                    window.billableTokens += record.getTotalTokens();
                    window.estimatedCostUsd = round(window.estimatedCostUsd + record.getEstimatedCostUsd());
                }
                case "FAILED" -> window.failedCount++;
                case "BLOCKED" -> window.blockedCount++;
                default -> {
                }
            }
        }
        return window;
    }

    private List<DailyUsagePoint> buildDailyTrend(List<UsageRecord> snapshot, int days) {
        Map<LocalDate, UsageWindow> buckets = new LinkedHashMap<>();
        LocalDate end = LocalDate.now(ZONE_ID);
        for (int i = days - 1; i >= 0; i--) {
            buckets.put(end.minusDays(i), new UsageWindow());
        }

        for (UsageRecord record : snapshot) {
            LocalDate date = Instant.ofEpochMilli(record.getTimestamp()).atZone(ZONE_ID).toLocalDate();
            UsageWindow bucket = buckets.get(date);
            if (bucket == null) {
                continue;
            }
            UsageWindow delta = aggregate(List.of(record));
            bucket.requestCount += delta.requestCount;
            bucket.successCount += delta.successCount;
            bucket.failedCount += delta.failedCount;
            bucket.blockedCount += delta.blockedCount;
            bucket.inputTokens += delta.inputTokens;
            bucket.outputTokens += delta.outputTokens;
            bucket.embeddingTokens += delta.embeddingTokens;
            bucket.billableTokens += delta.billableTokens;
            bucket.estimatedCostUsd = round(bucket.estimatedCostUsd + delta.estimatedCostUsd);
        }

        return buckets.entrySet().stream()
                .map(entry -> new DailyUsagePoint(entry.getKey().toString(), entry.getValue().getBillableTokens(), entry.getValue().getEstimatedCostUsd(), entry.getValue().getRequestCount()))
                .collect(Collectors.toList());
    }

    private List<ScopedUsagePoint> buildScopedSummaries(List<UsageRecord> snapshot, ScopeDimension dimension) {
        Map<String, List<UsageRecord>> grouped = new LinkedHashMap<>();
        for (UsageRecord record : snapshot) {
            String scopeKey = switch (dimension) {
                case MODEL -> buildModelKey(record.getProvider(), record.getModel());
                case NOVEL -> record.getNovelId() == null ? null : "novel-" + record.getNovelId();
            };
            if (scopeKey == null || scopeKey.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(scopeKey, key -> new ArrayList<>()).add(record);
        }

        return grouped.entrySet().stream()
                .map(entry -> {
                    UsageWindow window = aggregate(entry.getValue());
                    String label = dimension == ScopeDimension.MODEL
                            ? entry.getKey()
                            : entry.getKey().replace("novel-", "novelId=");
                    return ScopedUsagePoint.builder()
                            .scope(dimension.name())
                            .key(entry.getKey())
                            .label(label)
                            .requestCount(window.getRequestCount())
                            .successCount(window.getSuccessCount())
                            .failedCount(window.getFailedCount())
                            .blockedCount(window.getBlockedCount())
                            .billableTokens(window.getBillableTokens())
                            .estimatedCostUsd(window.getEstimatedCostUsd())
                            .build();
                })
                .sorted(Comparator.comparingInt(ScopedUsagePoint::getBillableTokens).reversed())
                .collect(Collectors.toList());
    }

    private SettingsSnapshot toSettingsSnapshot() {
        AiProperties.CostControl settings = aiProperties.getCostControl();
        AiProperties.Pricing pricing = settings.getPricing();
        return SettingsSnapshot.builder()
                .enabled(settings.isEnabled())
                .strictMode(settings.isStrictMode())
                .recentRecords(settings.getRecentRecords())
                .maxEstimatedTokensPerRequest(settings.getMaxEstimatedTokensPerRequest())
                .reservedCompletionTokens(settings.getReservedCompletionTokens())
                .dailyTokenBudget(settings.getDailyTokenBudget())
                .monthlyTokenBudget(settings.getMonthlyTokenBudget())
                .dailyBudgetUsd(settings.getDailyBudgetUsd())
                .monthlyBudgetUsd(settings.getMonthlyBudgetUsd())
                .perNovelDailyTokenBudget(settings.getPerNovelDailyTokenBudget())
                .perNovelDailyBudgetUsd(settings.getPerNovelDailyBudgetUsd())
                .perModelDailyTokenBudget(settings.getPerModelDailyTokenBudget())
                .perModelDailyBudgetUsd(settings.getPerModelDailyBudgetUsd())
                .degradeOnBudgetExceeded(settings.isDegradeOnBudgetExceeded())
                .degradeOnModelFailure(settings.isDegradeOnModelFailure())
                .degradeOnEmbeddingFailure(settings.isDegradeOnEmbeddingFailure())
                .currency(pricing.getCurrency())
                .inputPerMillionTokens(pricing.getInputPerMillionTokens())
                .outputPerMillionTokens(pricing.getOutputPerMillionTokens())
                .embeddingPerMillionTokens(pricing.getEmbeddingPerMillionTokens())
                .build();
    }

    private double calculateCostUsd(int inputTokens, int outputTokens, int embeddingTokens) {
        AiProperties.Pricing pricing = aiProperties.getCostControl().getPricing();
        double inputCost = inputTokens * pricing.getInputPerMillionTokens() / 1_000_000D;
        double outputCost = outputTokens * pricing.getOutputPerMillionTokens() / 1_000_000D;
        double embeddingCost = embeddingTokens * pricing.getEmbeddingPerMillionTokens() / 1_000_000D;
        return round(inputCost + outputCost + embeddingCost);
    }

    private String buildModelKey(String provider, String model) {
        return normalize(provider) + ":" + normalize(model);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 160 ? message : message.substring(0, 157) + "...";
    }

    private enum UsageType {
        CHAT, EMBEDDING
    }

    private enum UsageStatus {
        SUCCESS, FAILED, BLOCKED
    }

    private enum ScopeDimension {
        MODEL, NOVEL
    }

    @Data
    @Builder
    public static class UsageReservation {
        private String requestId;
        private long timestamp;
        private Long novelId;
        private UsageType usageType;
        private String provider;
        private String model;
        private String source;
        private int estimatedInputTokens;
        private int estimatedOutputTokens;
        private int estimatedEmbeddingTokens;
        private double estimatedCostUsd;
        private int charCount;
    }

    @Data
    @Builder
    public static class UsageRecord {
        private String requestId;
        private long timestamp;
        private Long novelId;
        private String usageType;
        private String status;
        private String provider;
        private String model;
        private String source;
        private int inputTokens;
        private int outputTokens;
        private int embeddingTokens;
        private int totalTokens;
        private double estimatedCostUsd;
        private int charCount;
        private String note;
    }

    @Data
    @Builder
    public static class DegradationEvent {
        private long timestamp;
        private String trigger;
        private String strategy;
        private String detail;
        private Long novelId;
        private String provider;
        private String model;
        private String source;
    }

    @Data
    @Builder
    public static class DashboardSummary {
        private SettingsSnapshot settings;
        private UsageWindow today;
        private UsageWindow month;
        private UsageWindow total;
        private List<DailyUsagePoint> dailyTrend;
        private List<ScopedUsagePoint> byModel;
        private List<ScopedUsagePoint> byNovel;
        private List<DegradationEvent> recentDegradations;
    }

    @Data
    @Builder
    public static class SettingsSnapshot {
        private boolean enabled;
        private boolean strictMode;
        private int recentRecords;
        private int maxEstimatedTokensPerRequest;
        private int reservedCompletionTokens;
        private long dailyTokenBudget;
        private long monthlyTokenBudget;
        private double dailyBudgetUsd;
        private double monthlyBudgetUsd;
        private long perNovelDailyTokenBudget;
        private double perNovelDailyBudgetUsd;
        private long perModelDailyTokenBudget;
        private double perModelDailyBudgetUsd;
        private boolean degradeOnBudgetExceeded;
        private boolean degradeOnModelFailure;
        private boolean degradeOnEmbeddingFailure;
        private String currency;
        private double inputPerMillionTokens;
        private double outputPerMillionTokens;
        private double embeddingPerMillionTokens;
    }

    @Data
    public static class SettingsUpdateRequest {
        private Boolean enabled;
        private Boolean strictMode;
        private Integer recentRecords;
        private Integer maxEstimatedTokensPerRequest;
        private Integer reservedCompletionTokens;
        private Long dailyTokenBudget;
        private Long monthlyTokenBudget;
        private Double dailyBudgetUsd;
        private Double monthlyBudgetUsd;
        private Long perNovelDailyTokenBudget;
        private Double perNovelDailyBudgetUsd;
        private Long perModelDailyTokenBudget;
        private Double perModelDailyBudgetUsd;
        private Boolean degradeOnBudgetExceeded;
        private Boolean degradeOnModelFailure;
        private Boolean degradeOnEmbeddingFailure;
        private PricingUpdateRequest pricing;
    }

    @Data
    public static class PricingUpdateRequest {
        private String currency;
        private Double inputPerMillionTokens;
        private Double outputPerMillionTokens;
        private Double embeddingPerMillionTokens;
    }

    @Data
    public static class UsageWindow {
        private int requestCount;
        private int successCount;
        private int failedCount;
        private int blockedCount;
        private int inputTokens;
        private int outputTokens;
        private int embeddingTokens;
        private int billableTokens;
        private double estimatedCostUsd;
    }

    @Data
    @Builder
    public static class ScopedUsagePoint {
        private String scope;
        private String key;
        private String label;
        private int requestCount;
        private int successCount;
        private int failedCount;
        private int blockedCount;
        private int billableTokens;
        private double estimatedCostUsd;
    }

    @Data
    public static class DailyUsagePoint {
        private final String date;
        private final int tokens;
        private final double costUsd;
        private final int requestCount;
    }
}
