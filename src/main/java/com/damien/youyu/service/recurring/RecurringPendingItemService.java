package com.damien.youyu.service.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.PostMode;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.service.LedgerAccountResolver;
import com.damien.youyu.service.TransactionService;

/**
 * 待确认生成项服务：本特性「懒生成为事实源」的入口（tasks 4.1）。当用户打开周期记账相关视图或查询待确认项时，
 * 由本服务按当前账本下每条 {@link RuleStatus#ACTIVE} 规则<b>惰性补齐</b>「已到期但表中尚无任何状态记录」的
 * 待确认项（需求 3.7）——期次由纯函数 {@link OccurrenceCalculator} 计算，是不依赖后台定时任务的<b>事实源</b>。
 *
 * <p>本类只实现 {@link #lazyGenerate}；查询 / 确认 / 修改后确认 / 跳过 / 批量属后续任务（tasks 5.x）。</p>
 *
 * <h2>懒生成算法（design.md「懒生成算法（事实源）」）</h2>
 * <pre>
 * today = LocalDate.now(clock)                                   // Asia/Shanghai 自然日
 * for rule in ruleRepository.findByLedgerIdAndStatus(ledgerId, ACTIVE):   // PAUSED 不进入扫描（需求 3.5、6.1）
 *   try:
 *     for d in calculator.occurrencesUpTo(toRuleSpec(rule), today):       // 已到期期次（升序、含结束条件）
 *       if d &lt; generationLowerBound(rule): continue                     // 恢复/暂停不回补（需求 6.2）
 *       if pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule, d): continue  // 已有任一状态记录则跳过
 *       try:  generator.generate(rule, d)   // REQUIRES_NEW，独立事务写入一条 PENDING 快照
 *       catch DataIntegrityViolationException: ignore   // 并发/重复撞唯一键：视为已生成，不报错（需求 3.4、9.4）
 *   catch Exception e:
 *     log.warn("[RECURRING_GEN_FAILED] ruleId={}", rule.id, e)  // 单规则失败就地隔离（需求 3.8）
 * </pre>
 *
 * <h2>事务边界（为何不开外层大事务）</h2>
 * <p>本方法<b>刻意不加 {@code @Transactional}</b>：若把整批插入放进一个事务，某一期次撞唯一约束
 * {@code uk_recurring_pending_rule_date} 会在 flush 时把整个事务标记为 rollback-only，导致其余期次 / 其余规则
 * 连坐回滚（常见 JPA 陷阱）。因此每条插入下沉到 {@link RecurringPendingItemGenerator#generate}
 * 的 {@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} <b>独立事务</b>——
 * 撞键只回滚该新事务，异常抛回本方法<b>就地捕获静默</b>（需求 3.4、9.4），外层无事务可被毒化，
 * 其余期次与其余规则的生成不受影响。{@code existsByRuleIdAndOccurrenceDate} 先行预检只为减少无谓插入尝试，
 * 库侧幂等兜底仍由唯一约束承担。</p>
 *
 * <h2>失败隔离与零触账</h2>
 * <ul>
 *   <li><b>单规则失败隔离（需求 3.8）：</b>每条规则的补齐包在 try/catch 内，某规则抛异常仅记
 *       {@code [RECURRING_GEN_FAILED]} 告警日志，不阻断同账本其余规则的补齐，也不阻断已有待确认项的返回。</li>
 *   <li><b>暂停不生成（需求 3.5、6.1）：</b>{@code PAUSED} 规则不在
 *       {@link RecurringRuleRepository#findByLedgerIdAndStatus} 的 {@code ACTIVE} 结果内，不进入扫描。</li>
 *   <li><b>生成期零触账（需求 3.2）：</b>生成只写 {@code recurring_pending_items}，不创建任何交易、
 *       不改动任何账户余额。</li>
 * </ul>
 *
 * <h2>生成下界（generationLowerBound）</h2>
 * <p>对每条 {@code ACTIVE} 规则以 {@code max(startDate, updatedAt.toLocalDate())} 为扫描下界，跳过
 * {@code occurrenceDate < generationLowerBound} 的期次。这兑现
 * {@link com.damien.youyu.service.recurring.RecurringRuleService#resume} 约定的「恢复后不回补暂停区间」
 * （需求 6.2）：恢复时 {@code updated_at} 被置为恢复当日，暂停区间内（早于恢复当日）的期次因低于下界被跳过，
 * 恢复当日及之后的期次照常补齐。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Service
public class RecurringPendingItemService {

    private static final Logger log = LoggerFactory.getLogger(RecurringPendingItemService.class);

    private final RecurringRuleRepository ruleRepository;
    private final RecurringPendingItemRepository pendingItemRepository;
    private final RecurringPendingItemGenerator generator;
    private final RecurringAutoPoster autoPoster;
    private final RecurringAutoPostNotifier autoPostNotifier;
    private final TransactionService transactionService;
    private final CategoryRepository categoryRepository;
    private final LedgerAccountResolver accountResolver;
    private final RecurringTemplateValidator templateValidator;
    private final OccurrenceCalculator calculator;
    private final Clock clock;

    /**
     * 指向本 bean <b>自身 Spring 代理</b>的自引用，专供 {@link #batchConfirm} / {@link #batchSkip} 逐条经
     * <b>代理</b>调用 {@link #confirm} / {@link #skip}，使每条得以在<b>各自独立事务</b>内处理（需求 5.4、5.5）。
     *
     * <h2>为什么必须经代理自调用（Spring 自调用陷阱）</h2>
     * <p>{@link #confirm} / {@link #skip} 标注 {@code @Transactional}（默认 {@code REQUIRED} 传播）。若批量方法
     * 直接 {@code this.confirm(...)}，是<b>类内自调用</b>，会<b>绕过 Spring 事务代理</b>使
     * {@code @Transactional} 失效——批量方法本身不开事务时，单条确认的「闸门 + 建交易 + 回填」会各自成为无边界
     * 的独立操作，破坏单条原子性；批量方法若开大事务，则又变成一荣俱荣、一损俱损，某条失败毒化整批（需求 5.4
     * 要求「仅回滚该条、不影响其余」）。故通过<b>注入的自身代理</b> {@code self} 调用：批量方法<b>刻意不加
     * {@code @Transactional}</b>（自身无事务上下文），每次 {@code self.confirm(...)} / {@code self.skip(...)} 命中
     * 代理后以 {@code REQUIRED} <b>各自开启一个新事务</b>并在返回前提交 / 回滚——某条失败只回滚该条事务，
     * 先前已提交的条目<b>原样保留</b>，其后条目继续处理（需求 5.4、5.5）。</p>
     *
     * <p>用 {@code @Lazy} 字段注入而非构造注入以打破「自己依赖自己」的构造期循环依赖；字段注入自身代理是处理
     * 该场景的惯用手法（与本包 {@link RecurringPendingItemGenerator} 单列独立 bean 规避自调用同源思路）。</p>
     */
    @Autowired
    @Lazy
    private RecurringPendingItemService self;

    public RecurringPendingItemService(
            RecurringRuleRepository ruleRepository,
            RecurringPendingItemRepository pendingItemRepository,
            RecurringPendingItemGenerator generator,
            RecurringAutoPoster autoPoster,
            RecurringAutoPostNotifier autoPostNotifier,
            TransactionService transactionService,
            CategoryRepository categoryRepository,
            LedgerAccountResolver accountResolver,
            RecurringTemplateValidator templateValidator,
            Clock clock) {
        this.ruleRepository = ruleRepository;
        this.pendingItemRepository = pendingItemRepository;
        this.generator = generator;
        this.autoPoster = autoPoster;
        this.autoPostNotifier = autoPostNotifier;
        this.transactionService = transactionService;
        this.categoryRepository = categoryRepository;
        this.accountResolver = accountResolver;
        this.templateValidator = templateValidator;
        this.clock = clock;
        this.calculator = new OccurrenceCalculator();
    }

    /**
     * 懒生成：为当前账本下每条 {@link RuleStatus#ACTIVE} 规则补齐「到期日已到期、不早于生成下界且表中尚无
     * 任何状态记录」的 {@code PENDING} 待确认项（需求 3.1、3.7）。查询待确认项前先调用本方法，即可让展示始终
     * 反映最新到期情况而不依赖后台定时任务。
     *
     * <p>幂等且失败隔离：重复调用不产生重复记录（唯一约束 + 存在性预检，需求 3.3、3.4）；单条规则补齐失败
     * 就地隔离、仅记告警日志，不阻断其余规则与已有项（需求 3.8）。生成过程不创建交易、不改账户余额（需求 3.2）。</p>
     *
     * @param ledgerId 当前账本 id（账本隔离：只扫描并生成归属该账本的规则的待确认项）
     */
    public void lazyGenerate(Long ledgerId) {
        LocalDate today = LocalDate.now(clock);
        List<RecurringRule> activeRules =
                ruleRepository.findByLedgerIdAndStatus(ledgerId, RuleStatus.ACTIVE);
        for (RecurringRule rule : activeRules) {
            try {
                generateForRule(rule, today);
            } catch (Exception e) {
                // 单规则补齐失败就地隔离：不阻断同账本其余规则的补齐，也不阻断已有待确认项的返回（需求 3.8）。
                log.warn("[RECURRING_GEN_FAILED] ruleId={}", rule.getId(), e);
            }
        }
    }

    /**
     * 待确认项查询（需求 3.7、5.1、5.2、5.3、8.4）：先触发懒生成（需求 3.7）让展示反映最新到期情况，
     * 再返回当前账本状态为 {@link PendingStatus#PENDING} 的待确认项列表。每项（{@link RecurringPendingItem}）
     * 已携带来源规则 id（{@code ruleId}）、期次到期日（{@code occurrenceDate}）与生成时的模板快照字段
     * （{@code type}/{@code amount}/{@code categoryId}/{@code accountId}/{@code note}），供上层控制器（tasks 7.2）
     * 装配为响应 DTO。
     *
     * <h2>确定性排序（需求 5.2）</h2>
     * <p>严格按「期次到期日升序 → 来源规则创建时间升序 → 待确认项 id 升序」排列，保证任意两次查询对相同数据
     * 返回完全一致且可复现的顺序。仓库
     * {@link RecurringPendingItemRepository#findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc}
     * 只能提供「到期日 → 项 id」两级排序，缺失中间的「规则创建时间」维度；本方法批量载入这些项涉及的规则，
     * 取各自 {@code created_at} 补齐中间键后在服务层重排，兑现需求 5.2 的三级排序。</p>
     *
     * <h2>账本隔离与空结果</h2>
     * <ul>
     *   <li><b>账本隔离（需求 8.4）：</b>仓库查询按 {@code ledgerId} 过滤，只返回归属当前账本的项；
     *       跨账本的待确认项不可见。</li>
     *   <li><b>无待确认项（需求 5.1）：</b>当前账本无任何 {@code PENDING} 项时返回空列表，不返回错误。</li>
     * </ul>
     *
     * @param ledgerId 当前账本 id（账本隔离：只生成并返回归属该账本的待确认项）
     * @return 当前账本按需求 5.2 确定性排序的 {@code PENDING} 待确认项列表；无则为空列表
     */
    public List<RecurringPendingItem> queryPendingItems(Long ledgerId) {
        // 先懒生成，让查询反映最新到期情况（需求 3.7）。
        lazyGenerate(ledgerId);

        List<RecurringPendingItem> pending =
                pendingItemRepository.findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(
                        ledgerId, PendingStatus.PENDING);
        if (pending.isEmpty()) {
            // 无 PENDING 返回空列表，不报错（需求 5.1）。
            return pending;
        }

        // 批量载入这些项涉及的规则，取 created_at 作为需求 5.2 排序的中间键（避免逐项回表）。
        Map<Long, LocalDateTime> ruleCreatedAt = loadRuleCreatedAt(pending);

        // 确定性三级排序：到期日升序 → 规则创建时间升序 → 项 id 升序（需求 5.2）。
        Comparator<RecurringPendingItem> ordering = Comparator
                .comparing(RecurringPendingItem::getOccurrenceDate)
                .thenComparing(item -> ruleCreatedAt.getOrDefault(item.getRuleId(), LocalDateTime.MIN))
                .thenComparing(RecurringPendingItem::getId);
        pending.sort(ordering);
        return pending;
    }

    /**
     * 确认入账（单条，含「修改后确认」，tasks 5.2；需求 4.1、4.2、4.3、4.5、4.6、4.7、4.8、4.9、9.7）。
     * 用户对一条 {@link PendingStatus#PENDING} 待确认项确认后，走既有 {@link TransactionService#create}
     * 生成一条真实流水并按既有口径更新账户余额，随后将该项置 {@link PendingStatus#CONFIRMED} 并回填
     * {@code confirmedTransactionId}。可携带修改后的字段实现「修改后确认」。
     *
     * <h2>取值：覆盖优先，否则用快照（需求 4.3）</h2>
     * <p>本次入账的每个字段取「覆盖值（非 {@code null}）优先，否则该项生成时的模板快照」：金额 /
     * 分类 / 账户 / 备注 / 记账时间可被覆盖；<b>类型不可改</b>（沿用快照 {@code type}）。记账时间缺省取该项
     * 期次到期日的当地 00:00（{@code Asia/Shanghai}），使流水日期落在应记账当日而非确认当日。<b>修改后确认
     * 只影响本次入账取值</b>，绝不改动来源规则的模板字段，也不改变该项的 {@code occurrenceDate} 与生成幂等
     * 唯一键 {@code (rule_id, occurrence_date)}（需求 4.3）。
     *
     * <h2>校验（需求 4.6、4.8）——失败零副作用</h2>
     * <ul>
     *   <li><b>金额 / 备注</b>：经共享校验器 {@link RecurringTemplateValidator} 重跑与创建一致的校验，
     *       越界 / 小数位超限 → {@code AMOUNT_INVALID}，备注超长 → {@code NOTE_TOO_LONG}（需求 4.8）。</li>
     *   <li><b>分类 / 账户</b>：有效分类须属当前账本、有效账户须为当前用户在当前账本可用的账户；任一在当前
     *       账本已不存在 / 不可用 → {@code RECURRING_ITEM_TARGET_MISSING}（{@code field} 指明是
     *       {@code categoryId} 还是 {@code accountId}），项保持 {@code PENDING}（需求 4.6）。</li>
     * </ul>
     * <p>全部校验前置于并发闸门与建交易之前：校验失败即抛 {@link ApiException}，事务回滚，项保持
     * {@code PENDING}、不生成流水、不改余额（需求 4.8）。</p>
     *
     * <h2>单事务原子 + 乐观并发（需求 4.2、4.9）</h2>
     * <p>本方法 {@code @Transactional}：闸门（置 {@code CONFIRMED}）、建交易、回填 {@code
     * confirmedTransactionId} 同处一个事务，全部提交或全部回滚（{@link TransactionService#create} 以默认
     * {@code REQUIRES_NEW} 之外的默认传播加入本事务）。并发 / 重复确认经
     * {@link RecurringPendingItemRepository#markConfirmedIfPending} 的条件更新串行化：仅一个事务命中
     * {@code status=PENDING}（返回 1）继续建交易，其余返回 0 抛
     * {@code RECURRING_ITEM_ALREADY_PROCESSED}——因闸门先于建交易，落败者不进入建交易 / 改余额，
     * 从而至多一条流水、至多一次余额变动（需求 4.9）。任一步失败整体回滚，项回到 {@code PENDING}（需求 4.2）。</p>
     *
     * <h2>归属与状态（需求 4.5、8.4、8.5）</h2>
     * <p>先按项 id 定位并校验其归属当前账本、且其来源规则归属当前用户 + 当前账本（复用与
     * {@link RecurringRuleService} 一致的 {@code NOT_FOUND} 语义，不泄漏跨租户存在性）。对非 {@code PENDING}
     * （已 {@code CONFIRMED}/{@code SKIPPED}）的项确认 → {@code RECURRING_ITEM_ALREADY_PROCESSED}（需求 4.5）。</p>
     *
     * <p>入账流水复用 {@link TransactionService#create}，故与普通手动记账在流水列表 / 统计 / 账户余额中口径
     * 一致（需求 4.1、4.7、9.7）；金额一律 {@code BigDecimal} 保留 2 位小数（需求 9.7）。</p>
     *
     * @param userId           当前用户（归属校验）
     * @param ledgerId         当前账本 id（账本隔离）
     * @param itemId           待确认项 id
     * @param amountOverride   覆盖金额；{@code null} 用快照
     * @param categoryIdOverride 覆盖分类 id；{@code null} 用快照
     * @param accountIdOverride  覆盖账户 id；{@code null} 用快照
     * @param noteOverride     覆盖备注；{@code null} 用快照
     * @param occurredAtOverride 覆盖记账时间；{@code null} 取期次到期日 00:00（{@code Asia/Shanghai}）
     * @return 已确认的待确认项（{@code status=CONFIRMED}，{@code confirmedTransactionId} 指向新流水）
     * @throws ApiException NOT_FOUND / RECURRING_ITEM_ALREADY_PROCESSED / RECURRING_ITEM_TARGET_MISSING /
     *                      AMOUNT_INVALID / NOTE_TOO_LONG
     */
    @Transactional
    public RecurringPendingItem confirm(Long userId, Long ledgerId, Long itemId,
            BigDecimal amountOverride, Long categoryIdOverride, Long accountIdOverride,
            String noteOverride, LocalDateTime occurredAtOverride) {

        // 1) 归属定位（需求 8.4、8.5）：项须归属当前账本，且其来源规则归属当前用户 + 当前账本。
        //    不存在 / 跨账本 / 跨用户一律 NOT_FOUND，不泄漏存在性（与 RecurringRuleService 同款语义）。
        RecurringPendingItem item = pendingItemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));
        if (!ledgerId.equals(item.getLedgerId())) {
            throw ApiException.notFound("待确认项不存在");
        }
        ruleRepository.findByIdAndUserIdAndLedgerId(item.getRuleId(), userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));

        // 2) 状态校验（需求 4.5）：非 PENDING（已确认 / 已跳过）→ 该项已处理。
        if (item.getStatus() != PendingStatus.PENDING) {
            throw ApiException.recurringItemAlreadyProcessed();
        }

        // 3) 取值：覆盖优先，否则快照（需求 4.3）。类型不可改，沿用快照。
        BigDecimal effectiveAmount = amountOverride != null ? amountOverride : item.getAmount();
        Long effectiveCategoryId = categoryIdOverride != null ? categoryIdOverride : item.getCategoryId();
        Long effectiveAccountId = accountIdOverride != null ? accountIdOverride : item.getAccountId();
        String effectiveNote = noteOverride != null ? noteOverride : item.getNote();
        LocalDateTime effectiveOccurredAt = occurredAtOverride != null
                ? occurredAtOverride
                : item.getOccurrenceDate().atStartOfDay();
        String type = item.getType();

        // 4) 重跑创建口径校验（需求 4.8）：金额 / 备注复用共享校验器（AMOUNT_INVALID / NOTE_TOO_LONG）。
        BigDecimal amount = templateValidator.validateAmount(effectiveAmount);
        String note = templateValidator.validateNote(effectiveNote);
        // 分类 / 账户在当前账本已不存在 / 不可用 → RECURRING_ITEM_TARGET_MISSING（需求 4.6）。
        validateTargetsPresent(userId, ledgerId, effectiveCategoryId, effectiveAccountId);

        // 5) 乐观并发闸门（需求 4.9）：条件更新 status=PENDING → CONFIRMED，先于建交易。
        //    仅抢到者继续建交易，落败者（返回 0）得 RECURRING_ITEM_ALREADY_PROCESSED，全程至多一条流水。
        LocalDateTime now = LocalDateTime.now(clock);
        int gated = pendingItemRepository.markConfirmedIfPending(itemId, now);
        if (gated == 0) {
            throw ApiException.recurringItemAlreadyProcessed();
        }

        // 6) 走既有交易创建链路：账户加锁 + 单事务原子余额更新（需求 4.1、4.2、4.7、9.7）。
        //    与闸门 / 回填同处本事务，任一步失败整体回滚，项回到 PENDING、不生成流水、不改余额。
        Transaction tx = transactionService.create(userId, ledgerId, type, amount,
                effectiveAccountId, effectiveCategoryId, effectiveOccurredAt, note);

        // 7) 回填 confirmed_transaction_id（状态已由闸门置 CONFIRMED；闸门 clearAutomatically 后回库读最新态）。
        RecurringPendingItem confirmed = pendingItemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));
        confirmed.setConfirmedTransactionId(tx.getId());
        confirmed.setUpdatedAt(now);
        return pendingItemRepository.save(confirmed);
    }

    /**
     * 跳过本期（tasks 5.3；需求 4.4、4.5）。用户对一条 {@link PendingStatus#PENDING} 待确认项执行跳过后，
     * 系统将其状态置为 {@link PendingStatus#SKIPPED}，且<b>不生成任何流水、不改变任何账户余额</b>（需求 4.4）——
     * 与确认入账不同，跳过是纯粹的状态迁移，不触碰交易 / 余额链路。
     *
     * <h2>归属定位（需求 8.4、8.5）——与 {@link #confirm} 同款语义</h2>
     * <p>先按项 id 定位并校验其归属当前账本、且其来源规则归属当前用户 + 当前账本；不存在 / 跨账本 / 跨用户
     * 一律 {@code NOT_FOUND}，不泄漏跨租户存在性（复用与 {@link RecurringRuleService}、{@link #confirm} 一致的
     * 定位口径）。</p>
     *
     * <h2>状态机与乐观并发（需求 4.5、4.9）</h2>
     * <p>本方法 {@code @Transactional}。经
     * {@link RecurringPendingItemRepository#markSkippedIfPending} 的条件更新（{@code status=PENDING → SKIPPED}）
     * 完成跳过：数据库行锁使并发 / 重复的跳过 / 确认串行化，仅一个事务命中 {@code PENDING}（返回 1）成功跳过，
     * 其余（含并发确认的落败者、已 {@code CONFIRMED}/{@code SKIPPED} 的再次操作）返回 0，抛
     * {@code RECURRING_ITEM_ALREADY_PROCESSED}（需求 4.5）。因跳过不建交易 / 不改余额，无任何可回滚的副作用。</p>
     *
     * <p>先做一次显式状态预检（非 {@code PENDING} 即抛 {@code RECURRING_ITEM_ALREADY_PROCESSED}）以给出即时的
     * 已处理反馈；条件更新兜住预检与更新之间的并发窗口，二者共同保证「PENDING → 终态」至多迁移一次。</p>
     *
     * @param userId   当前用户（归属校验）
     * @param ledgerId 当前账本 id（账本隔离）
     * @param itemId   待确认项 id
     * @return 已跳过的待确认项（{@code status=SKIPPED}）
     * @throws ApiException NOT_FOUND（不存在 / 跨账本 / 跨用户）/ RECURRING_ITEM_ALREADY_PROCESSED（非 PENDING）
     */
    @Transactional
    public RecurringPendingItem skip(Long userId, Long ledgerId, Long itemId) {
        // 1) 归属定位（需求 8.4、8.5）：项须归属当前账本，且其来源规则归属当前用户 + 当前账本。
        //    不存在 / 跨账本 / 跨用户一律 NOT_FOUND，不泄漏存在性（与 confirm 同款语义）。
        RecurringPendingItem item = pendingItemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));
        if (!ledgerId.equals(item.getLedgerId())) {
            throw ApiException.notFound("待确认项不存在");
        }
        ruleRepository.findByIdAndUserIdAndLedgerId(item.getRuleId(), userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));

        // 2) 状态校验（需求 4.5）：非 PENDING（已确认 / 已跳过）→ 该项已处理。
        if (item.getStatus() != PendingStatus.PENDING) {
            throw ApiException.recurringItemAlreadyProcessed();
        }

        // 3) 乐观并发闸门（需求 4.5、4.9）：条件更新 status=PENDING → SKIPPED。
        //    仅一个事务命中 PENDING（返回 1）成功；并发落败 / 已处理（返回 0）→ RECURRING_ITEM_ALREADY_PROCESSED。
        //    跳过不生成流水、不改余额（需求 4.4）——本条件更新即完成整个跳过动作。
        LocalDateTime now = LocalDateTime.now(clock);
        int gated = pendingItemRepository.markSkippedIfPending(itemId, now);
        if (gated == 0) {
            throw ApiException.recurringItemAlreadyProcessed();
        }

        // 4) 回库读最新态返回（闸门 clearAutomatically 后同事务查询回库，避免陈旧受管实体）。
        return pendingItemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("待确认项不存在"));
    }

    /**
     * 批量确认（tasks 5.4；需求 5.4、5.6）。对给定的一组待确认项 id <b>逐条在各自独立事务内</b>按
     * {@link #confirm} 的单条口径确认入账（走既有交易创建链路、更新余额、置 {@code CONFIRMED}），返回逐条
     * 结果与成功 / 失败计数（{@link RecurringBatchResult}），使部分失败的处理可被调用方逐条判定（需求 5.6）。
     *
     * <h2>逐条独立事务与失败隔离（需求 5.4）</h2>
     * <p>每条经<b>注入的自身代理</b> {@code self.confirm(...)} 调用（见字段 {@link #self} 说明）：本方法
     * <b>刻意不加 {@code @Transactional}</b>，故每次 {@code confirm} 以 {@code REQUIRED} 各自开启一个新事务并在
     * 返回前提交 / 回滚。某条失败（如 {@code RECURRING_ITEM_ALREADY_PROCESSED} / {@code RECURRING_ITEM_TARGET_MISSING}
     * / {@code NOT_FOUND} / 校验失败）<b>仅回滚该条事务、使其保持原状态</b>，异常在本循环内就地捕获转为一条
     * 失败结果，<b>不影响也不回滚</b>先前已提交的条目与其后条目的处理（需求 5.4）。非预期运行时异常同样就地
     * 捕获（其事务已回滚）、记 {@code [RECURRING_BATCH_CONFIRM_FAILED]} 告警并回退错误码
     * {@link RecurringBatchResult#INTERNAL_ERROR_CODE}，绝不中断整批。</p>
     *
     * <p>批量确认沿用默认取值（不做「修改后确认」）：{@code confirm} 各覆盖参数传 {@code null}，即以各项生成时
     * 的模板快照入账、记账时间取各自期次到期日 00:00（{@code Asia/Shanghai}）。归属校验、账本隔离、并发闸门
     * 均由单条 {@code confirm} 承担，故跨租户 / 跨账本条目在结果中记为 {@code NOT_FOUND} 失败（需求 8.4、8.5）。</p>
     *
     * @param userId   当前用户（归属校验，透传给单条 {@code confirm}）
     * @param ledgerId 当前账本 id（账本隔离，透传给单条 {@code confirm}）
     * @param itemIds  待确认项 id 列表；{@code null} / 空视为无待处理条目，返回空结果
     * @return 逐条处理结果与成功 / 失败计数（{@link RecurringBatchResult}）
     */
    public RecurringBatchResult batchConfirm(Long userId, Long ledgerId, List<Long> itemIds) {
        List<Long> succeeded = new ArrayList<>();
        List<RecurringBatchResult.Failure> failed = new ArrayList<>();
        if (itemIds != null) {
            for (Long itemId : itemIds) {
                try {
                    // 经自身代理调用：以 REQUIRED 各自开启独立事务，某条失败只回滚该条（需求 5.4）。
                    self.confirm(userId, ledgerId, itemId, null, null, null, null, null);
                    succeeded.add(itemId);
                } catch (ApiException e) {
                    // 已处理 / 目标缺失 / 跨租户 / 校验失败等：该条事务已回滚、保持原状态，记为失败不影响其余。
                    failed.add(new RecurringBatchResult.Failure(itemId, e.getCode()));
                } catch (Exception e) {
                    // 非预期异常：该条事务已回滚，就地隔离不中断整批（需求 5.4）。
                    log.warn("[RECURRING_BATCH_CONFIRM_FAILED] itemId={}", itemId, e);
                    failed.add(new RecurringBatchResult.Failure(
                            itemId, RecurringBatchResult.INTERNAL_ERROR_CODE));
                }
            }
        }
        return RecurringBatchResult.of(succeeded, failed);
    }

    /**
     * 批量跳过（tasks 5.4；需求 5.5、5.6）。对给定的一组待确认项 id <b>逐条在各自独立事务内</b>按
     * {@link #skip} 的单条口径处理：其中状态为 {@link PendingStatus#PENDING} 的置为 {@link PendingStatus#SKIPPED}
     * 且<b>不生成任何流水、不改变任何账户余额</b>；已处理（{@code CONFIRMED}/{@code SKIPPED}）的条目在结果中
     * <b>标记失败</b>（{@code RECURRING_ITEM_ALREADY_PROCESSED}）而<b>不影响其余各条</b>（需求 5.5）。返回逐条
     * 结果与成功 / 失败计数（{@link RecurringBatchResult}），部分失败可逐条判定（需求 5.6）。
     *
     * <h2>逐条独立事务与失败隔离（需求 5.5）</h2>
     * <p>与 {@link #batchConfirm} 同款：每条经<b>注入的自身代理</b> {@code self.skip(...)} 调用，本方法不加
     * {@code @Transactional}，每次 {@code skip} 以 {@code REQUIRED} 各自开启新事务；某条失败仅回滚该条、
     * 使其保持原状态，异常在循环内就地捕获转为失败结果，不影响先前已提交与其后条目（需求 5.5）。跨租户 /
     * 跨账本条目由单条 {@code skip} 判定为 {@code NOT_FOUND}，记为失败（需求 8.4、8.5）。非预期运行时异常
     * 就地捕获、记 {@code [RECURRING_BATCH_SKIP_FAILED]} 告警并回退错误码
     * {@link RecurringBatchResult#INTERNAL_ERROR_CODE}，绝不中断整批。</p>
     *
     * @param userId   当前用户（归属校验，透传给单条 {@code skip}）
     * @param ledgerId 当前账本 id（账本隔离，透传给单条 {@code skip}）
     * @param itemIds  待确认项 id 列表；{@code null} / 空视为无待处理条目，返回空结果
     * @return 逐条处理结果与成功 / 失败计数（{@link RecurringBatchResult}）
     */
    public RecurringBatchResult batchSkip(Long userId, Long ledgerId, List<Long> itemIds) {
        List<Long> succeeded = new ArrayList<>();
        List<RecurringBatchResult.Failure> failed = new ArrayList<>();
        if (itemIds != null) {
            for (Long itemId : itemIds) {
                try {
                    // 经自身代理调用：以 REQUIRED 各自开启独立事务，某条失败只回滚该条（需求 5.5）。
                    self.skip(userId, ledgerId, itemId);
                    succeeded.add(itemId);
                } catch (ApiException e) {
                    // 已处理（CONFIRMED/SKIPPED）/ 跨租户等：记为失败不影响其余（需求 5.5）。
                    failed.add(new RecurringBatchResult.Failure(itemId, e.getCode()));
                } catch (Exception e) {
                    // 非预期异常：该条事务已回滚，就地隔离不中断整批（需求 5.5）。
                    log.warn("[RECURRING_BATCH_SKIP_FAILED] itemId={}", itemId, e);
                    failed.add(new RecurringBatchResult.Failure(
                            itemId, RecurringBatchResult.INTERNAL_ERROR_CODE));
                }
            }
        }
        return RecurringBatchResult.of(succeeded, failed);
    }

    /**
     * 确认时校验有效分类 / 账户在当前账本仍存在 / 可用（需求 4.6）。分类须属当前账本（同
     * {@link TransactionService} 口径）；账户须在当前用户于当前账本的可选集内（复用
     * {@link LedgerAccountResolver#selectableAccounts}）。任一缺失 → {@code RECURRING_ITEM_TARGET_MISSING}，
     * {@code field} 指明缺失项，项保持 {@code PENDING}、不建交易、不改余额。
     */
    private void validateTargetsPresent(Long userId, Long ledgerId, Long categoryId, Long accountId) {
        if (categoryId == null
                || categoryRepository.findByIdAndLedgerId(categoryId, ledgerId).isEmpty()) {
            throw ApiException.recurringItemTargetMissing("categoryId");
        }
        List<Account> selectable = accountResolver.selectableAccounts(userId, ledgerId);
        boolean usable = accountId != null
                && selectable.stream().anyMatch(a -> a.getId().equals(accountId));
        if (!usable) {
            throw ApiException.recurringItemTargetMissing("accountId");
        }
    }

    /**
     * 批量载入待确认项涉及规则的 {@code created_at}，构建 {@code ruleId → createdAt} 映射，用于需求 5.2
     * 排序的中间键。规则被删除会级联移除其 {@code PENDING} 项，故正常不会缺失；若个别缺失则该项在
     * {@link #queryPendingItems} 中回退为 {@link LocalDateTime#MIN}，仍保持确定性顺序。
     */
    private Map<Long, LocalDateTime> loadRuleCreatedAt(List<RecurringPendingItem> items) {
        Set<Long> ruleIds = new TreeSet<>();
        for (RecurringPendingItem item : items) {
            ruleIds.add(item.getRuleId());
        }
        Map<Long, LocalDateTime> createdAtByRule = new HashMap<>();
        for (RecurringRule rule : ruleRepository.findAllById(ruleIds)) {
            createdAtByRule.put(rule.getId(), rule.getCreatedAt());
        }
        return createdAtByRule;
    }

    /**
     * 补齐单条规则的全部「已到期、不早于生成下界且表中尚无记录」的期次。逐期次经
     * {@link RecurringPendingItemGenerator#generate} 的独立事务写入；撞唯一键就地捕获静默（需求 3.4、9.4）。
     */
    private void generateForRule(RecurringRule rule, LocalDate today) {
        RuleSpec spec = toRuleSpec(rule);
        LocalDate lowerBound = generationLowerBound(rule);
        // 入账方式分流（recurring-auto-post 需求 2.7、4.1）：AUTO 走自动入账 / 降级，CONFIRM 维持既有行为。
        boolean auto = rule.getPostMode() == PostMode.AUTO;
        for (LocalDate occurrenceDate : calculator.occurrencesUpTo(spec, today)) {
            // 恢复 / 暂停不回补：跳过早于生成下界的期次（需求 6.2）。
            if (occurrenceDate.isBefore(lowerBound)) {
                continue;
            }
            // 存在性预检：该规则该到期日已有任一状态（PENDING/CONFIRMED/SKIPPED）记录则跳过（需求 3.1、3.3）。
            if (pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.getId(), occurrenceDate)) {
                continue;
            }
            if (auto) {
                autoPostOccurrence(rule, occurrenceDate);
            } else {
                try {
                    generator.generate(rule, occurrenceDate);
                } catch (DataIntegrityViolationException duplicate) {
                    // 并发 / 重复生成撞唯一键 uk_recurring_pending_rule_date：视为该期次已生成，静默放弃本条，
                    // 不新增第二条、不向查询等主路径返回错误（需求 3.4、9.4）。
                    log.debug("周期待确认项已存在（唯一键幂等），ruleId={}, occurrenceDate={}",
                            rule.getId(), occurrenceDate);
                }
            }
        }
    }

    /**
     * 对一条 {@code AUTO} 规则的单个到期期次执行自动入账（recurring-auto-post 需求 2、3、4.1、4.3、5.3）。
     * 走 {@link RecurringAutoPoster#autoPost}（{@code REQUIRES_NEW} 独立事务）：
     * <ul>
     *   <li>{@link AutoPostResult.Outcome#AUTO_POSTED}：入账事务已提交，在其<b>事务边界之外</b>调
     *       {@link RecurringAutoPostNotifier#notifyAutoPosted} 告知用户（需求 5.3）；告知失败已被 notifier
     *       内部就地隔离，不影响入账。</li>
     *   <li>{@link AutoPostResult.Outcome#DEGRADED_TO_PENDING}：目标失效 / 金额非法已降级为一条 {@code PENDING}
     *       待确认项，无需告知（需求 3）。</li>
     *   <li>撞唯一键 {@link DataIntegrityViolationException}：并发 / 重复触发同一期次，另一路径已处理，静默
     *       （需求 2.4、3.4、4.3）。</li>
     *   <li>其它非预期运行时异常：<b>期次级就地隔离</b>，仅记 {@code [RECURRING_AUTOPOST_FAILED]} 告警，
     *       不阻断同规则其它期次、同账本其它规则（需求 3.5）。</li>
     * </ul>
     */
    private void autoPostOccurrence(RecurringRule rule, LocalDate occurrenceDate) {
        try {
            AutoPostResult result = autoPoster.autoPost(rule, occurrenceDate);
            if (result.autoPosted()) {
                // 入账事务已提交，事务边界外发告知（需求 5.3）；notifier 内部已就地隔离失败（需求 5.2）。
                autoPostNotifier.notifyAutoPosted(rule.getUserId(), result.transaction());
            }
        } catch (DataIntegrityViolationException duplicate) {
            // 并发 / 重复触发撞唯一键：另一路径已处理该期次，静默（需求 2.4、3.4、4.3）。
            log.debug("周期自动入账期次已处理（唯一键幂等），ruleId={}, occurrenceDate={}",
                    rule.getId(), occurrenceDate);
        } catch (Exception e) {
            // 期次级失败就地隔离：不阻断同规则其它期次、同账本其它规则（需求 3.5）。
            log.warn("[RECURRING_AUTOPOST_FAILED] ruleId={}, occurrenceDate={}",
                    rule.getId(), occurrenceDate, e);
        }
    }

    /**
     * 生成下界（design.md Glossary「生成下界」）：{@code max(startDate, updatedAt.toLocalDate())}。
     * 懒生成据此跳过早于下界的期次，兑现
     * {@link com.damien.youyu.service.recurring.RecurringRuleService#resume} 的「恢复后不回补暂停区间」
     * 契约（需求 6.2）。
     */
    static LocalDate generationLowerBound(RecurringRule rule) {
        LocalDate startDate = rule.getStartDate();
        LocalDate resumeAnchor = rule.getUpdatedAt().toLocalDate();
        return startDate.isAfter(resumeAnchor) ? startDate : resumeAnchor;
    }

    /**
     * 将 {@link RecurringRule} 的频率相关列投影为期次计算所需的纯值对象 {@link RuleSpec}
     * （不含金额 / 分类 / 账户等模板字段）。{@code weekly_days} 逗号串解析为星期几集合，交由
     * {@link RuleSpec} 归一化为不可变升序集合。
     */
    static RuleSpec toRuleSpec(RecurringRule rule) {
        return new RuleSpec(
                rule.getFrequency(),
                parseWeeklyDays(rule.getWeeklyDays()),
                rule.getMonthDay(),
                rule.isMonthEnd(),
                rule.getYearMonth(),
                rule.getYearDay(),
                rule.getStartDate(),
                rule.getEndCondition(),
                rule.getUntilDate(),
                rule.getCountN());
    }

    /** 解析稳定升序逗号串（如 {@code "1,3,5"}）为星期几集合；{@code null} / 空串 → 空集。 */
    private static Set<Integer> parseWeeklyDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<Integer> days = new TreeSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                days.add(Integer.valueOf(trimmed));
            }
        }
        return days;
    }
}
