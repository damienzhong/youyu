package com.damien.youyu.vision;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.CategoryClassifyRequest;
import com.damien.youyu.api.dto.CategoryClassifyResponse;
import com.damien.youyu.api.dto.ScreenshotRecognizeRequest;
import com.damien.youyu.api.dto.ScreenshotRecognizeResponse;
import com.damien.youyu.config.VisionProperties;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.CategorySuggestService;
import com.damien.youyu.service.LedgerAccountResolver;
import com.damien.youyu.service.TransactionService;
import com.damien.youyu.vision.ScreenshotParser.Direction;
import com.damien.youyu.vision.ScreenshotParser.Entry;
import com.damien.youyu.vision.ScreenshotParser.Screen;
import com.damien.youyu.vision.ScreenshotParser.Shot;

/**
 * 截图识别：截图 → 视觉模型照抄文字 → 代码解析校验 → 自动分类 → 查账本重复 → 返回预览。
 *
 * <p>只做预览，不写账本：用户在核对页确认后，前端走既有的 {@code POST /api/imports/bills} 入账
 * （{@code externalId = "shot:<批次>:<序号>"}，便于整批撤销）。</p>
 *
 * <p>隐私：截图只在内存里转发给模型，不落盘、不进库、不打日志。额度：识别前按张数预占，
 * 识别失败、不是账单的截图退回额度。</p>
 */
@Service
public class ScreenshotRecognizeService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotRecognizeService.class);

    /** 账本查重的时间窗口：同金额、同收支、时间相差不超过这个值视为疑似重复。 */
    static final Duration DUP_WINDOW = Duration.ofMinutes(10);

    /** 截图批次号：32 位小写十六进制（前端 UUID 去横线）。 */
    private static final Pattern BATCH = Pattern.compile("^[0-9a-f]{32}$");

    /** 整批撤销的时限。 */
    static final Duration UNDO_WINDOW = Duration.ofHours(24);

    private final VisionProperties props;
    private final VisionModelClient client;
    private final VisionQuotaService quota;
    private final CategorySuggestService categorySuggest;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerAccountResolver accountResolver;
    private final TransactionService transactionService;
    private final Clock clock;

    public ScreenshotRecognizeService(VisionProperties props, VisionModelClient client, VisionQuotaService quota,
            CategorySuggestService categorySuggest, CategoryRepository categoryRepository,
            TransactionRepository transactionRepository, LedgerAccountResolver accountResolver,
            TransactionService transactionService, Clock clock) {
        this.props = props;
        this.client = client;
        this.quota = quota;
        this.categorySuggest = categorySuggest;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.accountResolver = accountResolver;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    /** 是否开启 + 今日剩余额度（前端进入识别前展示）。 */
    public record Status(boolean enabled, int remaining, int dailyLimit, int maxImages) {
    }

    public Status status(Long userId) {
        boolean on = props.enabled();
        return new Status(on, on ? quota.remaining(userId) : 0, props.getDailyLimit(), props.getMaxImages());
    }

    /** 已解码的一张截图。 */
    private record Img(byte[] bytes, String mime) {
    }

    /**
     * 识别一批截图。
     *
     * @throws ApiException VISION_DISABLED / VISION_INVALID / VISION_QUOTA_EXCEEDED /
     *                      VISION_UNAVAILABLE（全部截图调用失败，额度已退回）
     */
    public ScreenshotRecognizeResponse recognize(Long userId, Long ledgerId, ScreenshotRecognizeRequest req) {
        if (!props.enabled()) {
            throw ApiException.visionDisabled();
        }
        List<Img> images = decode(req);
        LocalDate day = quota.reserve(userId, images.size());

        LocalDateTime now = LocalDateTime.now(clock);
        List<Category> categories = categoryRepository.findByLedgerId(ledgerId);
        Map<String, Long> labelToId = new HashMap<>();
        List<String> expLabels = new ArrayList<>();
        List<String> incLabels = new ArrayList<>();
        buildLabels(categories, labelToId, expLabels, incLabels);
        String prompt = ScreenshotPrompt.build(now.toLocalDate(), expLabels, incLabels);
        List<Account> accounts = accountResolver.selectableAccounts(userId, ledgerId);

        List<ScreenshotRecognizeResponse.Shot> shots = new ArrayList<>();
        int notCounted = 0;
        int failed = 0;
        try {
            for (Img img : images) {
                Shot shot;
                try {
                    shot = ScreenshotParser.parse(client.recognize(prompt, img.bytes(), img.mime()), now);
                } catch (VisionModelClient.VisionCallException e) {
                    failed++;
                    notCounted++;
                    shots.add(new ScreenshotRecognizeResponse.Shot("FAILED", "", "", "", null, List.of(), 0));
                    continue;
                }
                if (shot.screen() == Screen.NOT_BILL || shot.entries().isEmpty() && shot.dropped() == 0) {
                    notCounted++;
                    shots.add(new ScreenshotRecognizeResponse.Shot("NOT_BILL", Screen.NOT_BILL.name(),
                            shot.app(), "", null, List.of(), 0));
                    continue;
                }
                shots.add(new ScreenshotRecognizeResponse.Shot("OK", shot.screen().name(), shot.app(),
                        shot.payAccount(), PayAccountMatcher.match(shot.payAccount(), shot.app(), accounts),
                        enrich(ledgerId, shot.entries(), labelToId), shot.dropped()));
            }
        } finally {
            quota.release(userId, day, notCounted);
        }
        if (failed == images.size()) {
            throw ApiException.visionUnavailable();
        }
        return new ScreenshotRecognizeResponse(shots, quota.remaining(userId), props.getDailyLimit());
    }

    /** 分类（规则 / 习惯 / 商户库优先，其次模型挑的分类，再其次分类名关键字）+ 账本查重。 */
    private List<ScreenshotRecognizeResponse.Entry> enrich(Long ledgerId, List<Entry> entries,
            Map<String, Long> labelToId) {
        List<CategoryClassifyRequest.Item> items = new ArrayList<>();
        for (Entry e : entries) {
            String type = e.direction() == Direction.INCOME || e.direction() == Direction.REFUND ? "income" : "expense";
            items.add(new CategoryClassifyRequest.Item(type, e.merchant(),
                    String.join(" ", e.billCategory(), e.item()).trim()));
        }
        List<CategoryClassifyResponse.Result> cls = categorySuggest.classify(ledgerId,
                new CategoryClassifyRequest(items)).results();
        List<Transaction> nearby = nearbyTransactions(ledgerId, entries);

        List<ScreenshotRecognizeResponse.Entry> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            CategoryClassifyResponse.Result r = cls.get(i);
            Long categoryId = r.categoryId();
            String source = r.source();
            if ("KEYWORD".equals(source) || "NONE".equals(source)) {
                String kind = e.direction() == Direction.INCOME || e.direction() == Direction.REFUND ? "INCOME" : "EXPENSE";
                Long ai = labelToId.get(kind + "|" + e.categoryGuess());
                if (ai != null) {
                    categoryId = ai;
                    source = "AI";
                }
            }
            out.add(new ScreenshotRecognizeResponse.Entry(e.direction().name(), e.amount(), e.occurredAt(),
                    e.dateOnly(), e.amountUncertain(), e.merchant(), e.item(), note(e), categoryId, source,
                    findDuplicate(e, nearby)));
        }
        return out;
    }

    private List<Transaction> nearbyTransactions(Long ledgerId, List<Entry> entries) {
        LocalDateTime min = null;
        LocalDateTime max = null;
        for (Entry e : entries) {
            if (e.occurredAt() == null) {
                continue;
            }
            LocalDateTime from = e.dateOnly() ? e.occurredAt().toLocalDate().atStartOfDay() : e.occurredAt().minus(DUP_WINDOW);
            LocalDateTime to = e.dateOnly() ? e.occurredAt().toLocalDate().atTime(23, 59, 59) : e.occurredAt().plus(DUP_WINDOW);
            min = min == null || from.isBefore(min) ? from : min;
            max = max == null || to.isAfter(max) ? to : max;
        }
        return min == null ? List.of() : transactionRepository.findByLedgerIdAndOccurredAtBetween(ledgerId, min, max);
    }

    /** 同金额、同收支、时间在窗口内（只看得出日期的按同一天）即疑似重复。 */
    private static ScreenshotRecognizeResponse.Duplicate findDuplicate(Entry e, List<Transaction> nearby) {
        if (e.occurredAt() == null || e.direction() == Direction.TRANSFER) {
            return null;
        }
        TransactionType type = e.direction() == Direction.EXPENSE ? TransactionType.EXPENSE : TransactionType.INCOME;
        for (Transaction t : nearby) {
            if (t.getType() != type || t.getAmount() == null || t.getAmount().compareTo(e.amount()) != 0) {
                continue;
            }
            boolean close = e.dateOnly()
                    ? t.getOccurredAt().toLocalDate().equals(e.occurredAt().toLocalDate())
                    : Duration.between(t.getOccurredAt(), e.occurredAt()).abs().compareTo(DUP_WINDOW) <= 0;
            if (close) {
                return new ScreenshotRecognizeResponse.Duplicate(t.getId(), t.getOccurredAt(), t.getAmount(), t.getNote());
            }
        }
        return null;
    }

    private static String note(Entry e) {
        String m = e.merchant() == null ? "" : e.merchant().trim();
        String it = e.item() == null ? "" : e.item().trim();
        if (m.isEmpty()) {
            return it;
        }
        return it.isEmpty() || it.equals(m) ? m : m + " · " + it;
    }

    /** 分类表：「父」或「父/子」，并建 (种类|标签) → id 的映射供模型结果回查。 */
    private static void buildLabels(List<Category> categories, Map<String, Long> labelToId,
            List<String> expLabels, List<String> incLabels) {
        Map<Long, Category> byId = new HashMap<>();
        for (Category c : categories) {
            byId.put(c.getId(), c);
        }
        for (Category c : categories) {
            if (c.getName() == null || c.getName().isBlank() || "余额调整".equals(c.getName())) {
                continue;
            }
            Category parent = c.getParentId() == null ? null : byId.get(c.getParentId());
            String label = parent == null ? c.getName() : parent.getName() + "/" + c.getName();
            labelToId.put(c.getKind() + "|" + label, c.getId());
            labelToId.putIfAbsent(c.getKind() + "|" + c.getName(), c.getId());
            (c.getKind() == CategoryKind.INCOME ? incLabels : expLabels).add(label);
        }
    }

    private List<Img> decode(ScreenshotRecognizeRequest req) {
        List<ScreenshotRecognizeRequest.Image> raw = req == null || req.images() == null ? List.of() : req.images();
        if (raw.isEmpty()) {
            throw ApiException.visionInvalid("请先选择截图");
        }
        if (raw.size() > props.getMaxImages()) {
            throw ApiException.visionInvalid("一次最多识别 " + props.getMaxImages() + " 张");
        }
        List<Img> out = new ArrayList<>();
        for (ScreenshotRecognizeRequest.Image i : raw) {
            String mime = i == null || i.mime() == null ? "image/jpeg" : i.mime().trim().toLowerCase();
            if (!mime.equals("image/jpeg") && !mime.equals("image/png") && !mime.equals("image/webp")) {
                throw ApiException.visionInvalid("只支持 JPG / PNG / WEBP 图片");
            }
            String data = i == null || i.data() == null ? "" : i.data().trim();
            int comma = data.startsWith("data:") ? data.indexOf(',') : -1;
            if (comma >= 0) {
                data = data.substring(comma + 1);
            }
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw ApiException.visionInvalid("图片数据损坏，请重新选择");
            }
            if (bytes.length == 0) {
                throw ApiException.visionInvalid("图片数据为空，请重新选择");
            }
            if (bytes.length > props.getMaxImageBytes()) {
                throw ApiException.visionInvalid("图片太大，请重新选择");
            }
            out.add(new Img(bytes, mime));
        }
        return out;
    }

    /**
     * 整批撤销：软删除本人在 24 小时内经截图识别入账的这一批流水（进回收站、回滚余额）。
     *
     * @return 撤销的笔数
     */
    @Transactional
    public int undo(Long userId, Long ledgerId, String batch) {
        String b = batch == null ? "" : batch.trim().toLowerCase();
        if (!BATCH.matcher(b).matches()) {
            throw ApiException.visionInvalid("批次号非法");
        }
        LocalDateTime since = LocalDateTime.now(clock).minus(UNDO_WINDOW);
        List<Transaction> txs = transactionRepository.findScreenshotBatch(ledgerId, userId, "shot:" + b + ":%", since);
        for (Transaction t : txs) {
            transactionService.delete(userId, ledgerId, t.getId());
        }
        log.info("[VISION_UNDO] userId={} ledgerId={} count={}", userId, ledgerId, txs.size());
        return txs.size();
    }
}
