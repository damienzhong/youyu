package com.damien.youyu.service.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.service.LedgerAccountResolver;

/**
 * 周期规则服务：周期规则的创建 / 校验与（后续任务的）生命周期管理。本类是周期记账后端的规则侧入口，
 * 与 AA 记账服务（{@code AaExpenseService}）同风格——控制器只做请求装配与 {@code CurrentUser} /
 * {@code CurrentLedger} 解析，全部字段校验、归属判定与持久化下沉到本服务。
 *
 * <p><b>已落地创建 + 校验（tasks 3.1）与查询 / 编辑（tasks 3.2）。</b>列表 / 详情按当前用户 + 当前账本
 * 隔离，越权 {@code NOT_FOUND}；编辑复用与创建完全一致的校验器，仅更新规则行本身、不触碰
 * {@code recurring_pending_items}（既有 {@code PENDING} 快照与 {@code CONFIRMED} 历史不受影响，需求 6.3、6.4）。</p>
 *
 * <p><b>生命周期（tasks 3.3）：</b>{@link #pause}（{@code ACTIVE}→{@code PAUSED}，既有 {@code PENDING} 不变）、
 * {@link #resume}（{@code PAUSED}→{@code ACTIVE}，以 {@code updated_at} 记录恢复当日作为懒生成的生成下界锚点，
 * 不回补暂停区间期次，需求 6.2）、{@link #delete}（级联移除全部 {@code PENDING}，保留 {@code CONFIRMED} 历史流水
 * 与 {@code SKIPPED} 记录，需求 6.5）。三者均按当前用户 / 当前账本归属定位，越权 {@code NOT_FOUND}，
 * 且不改动、不回滚任何已 {@code CONFIRMED} 流水 / 账户余额 / {@code SKIPPED} 记录（需求 6.6、6.7、8.5）。</p>
 *
 * <h2>创建校验口径（需求 1、2.10）</h2>
 * <ul>
 *   <li><b>模板字段：</b>类型仅 {@code expense}/{@code income}（不含 transfer）；金额 0.01–999,999,999.99
 *       且最多 2 位小数（小数位超限或越界复用 {@code AMOUNT_INVALID}）；分类须属当前账本；账户须为当前
 *       用户在当前账本可用的账户（复用 {@link LedgerAccountResolver#selectableAccounts}）；备注 ≤200。</li>
 *   <li><b>频率配置：</b>枚举合法；{@code WEEKLY} 星期几集合非空且每个 ∈ 1–7；{@code MONTHLY} 须有
 *       指定日（1–31）或「月末」标记；{@code YEARLY} 须有月（1–12）与日（1–31）。违者 {@code
 *       RECURRING_FREQUENCY_INVALID}。</li>
 *   <li><b>结束条件：</b>{@code UNTIL_DATE} 结束日不早于开始日期；{@code COUNT} 的 N ∈ 1–9999。违者
 *       {@code RECURRING_END_CONDITION_INVALID}。</li>
 *   <li><b>开始日期：</b>未指定时取创建当日（{@code Asia/Shanghai}）。</li>
 * </ul>
 *
 * <p>校验前置于任何写操作：拒绝即零副作用，不落任何规则（需求 1.4）。成功创建的规则归属当前用户 /
 * 当前账本，初始状态 {@link RuleStatus#ACTIVE}；{@code weekly_days} 规范化为稳定升序逗号串。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Service
public class RecurringRuleService {

    /** 模板类型：支出。 */
    public static final String TYPE_EXPENSE = "expense";
    /** 模板类型：收入。 */
    public static final String TYPE_INCOME = "income";

    /** COUNT 结束条件的期次总数范围（需求 1.7）。 */
    static final int COUNT_MIN = 1;
    static final int COUNT_MAX = 9999;

    /** 星期几取值范围（1=周一..7=周日，需求 2.10）。 */
    static final int WEEKDAY_MIN = 1;
    static final int WEEKDAY_MAX = 7;

    /** 每月指定日取值范围（需求 1.8）。 */
    static final int MONTH_DAY_MIN = 1;
    static final int MONTH_DAY_MAX = 31;

    /** 每年指定月 / 日取值范围（需求 1.8）。 */
    static final int YEAR_MONTH_MIN = 1;
    static final int YEAR_MONTH_MAX = 12;
    static final int YEAR_DAY_MIN = 1;
    static final int YEAR_DAY_MAX = 31;

    private final RecurringRuleRepository ruleRepository;
    private final RecurringPendingItemRepository pendingItemRepository;
    private final CategoryRepository categoryRepository;
    private final LedgerAccountResolver accountResolver;
    private final RecurringTemplateValidator templateValidator;
    private final Clock clock;

    public RecurringRuleService(
            RecurringRuleRepository ruleRepository,
            RecurringPendingItemRepository pendingItemRepository,
            CategoryRepository categoryRepository,
            LedgerAccountResolver accountResolver,
            RecurringTemplateValidator templateValidator,
            Clock clock) {
        this.ruleRepository = ruleRepository;
        this.pendingItemRepository = pendingItemRepository;
        this.categoryRepository = categoryRepository;
        this.accountResolver = accountResolver;
        this.templateValidator = templateValidator;
        this.clock = clock;
    }

    /**
     * 创建一条周期规则（需求 1.1–1.8、2.10）。全部字段校验前置于持久化，任一校验失败即抛
     * {@link ApiException} 且零副作用（不落任何规则）。
     *
     * @param userId       当前用户（规则所有者）
     * @param ledgerId     当前账本 id（账本隔离）
     * @param rawType      模板类型（{@code expense} / {@code income}）
     * @param rawAmount    模板金额（0.01–999,999,999.99，最多 2 位小数）
     * @param categoryId   模板分类 id（须属当前账本）
     * @param accountId    模板账户 id（须为当前用户在当前账本可用的账户）
     * @param rawNote      模板备注（≤200，可空）
     * @param frequency    频率节律（不可为 {@code null}）
     * @param weeklyDays   {@code WEEKLY} 星期几集合（1=周一..7=周日）；其余频率忽略
     * @param monthDay     {@code MONTHLY} 指定日（1–31）；{@code monthEnd=true} 或其余频率时可空
     * @param monthEnd     {@code MONTHLY}「月末」标记：真时每月取实际最后一日（忽略 {@code monthDay}）
     * @param yearMonth    {@code YEARLY} 指定月（1–12）；其余频率忽略
     * @param yearDay      {@code YEARLY} 指定日（1–31）；其余频率忽略
     * @param startDate    开始日期（{@code Asia/Shanghai} 自然日）；为空取创建当日
     * @param endCondition 结束条件（不可为 {@code null}）
     * @param untilDate    {@code UNTIL_DATE} 结束日期（不早于开始日期，含端点）
     * @param countN       {@code COUNT} 总期次数（1–9999）
     * @return 已保存的周期规则（含自增 id，初始状态 {@code ACTIVE}）
     * @throws ApiException RECURRING_RULE_INVALID / AMOUNT_INVALID / NOTE_TOO_LONG /
     *                      RECURRING_FREQUENCY_INVALID / RECURRING_END_CONDITION_INVALID
     */
    @Transactional
    public RecurringRule create(Long userId, Long ledgerId, String rawType, BigDecimal rawAmount,
            Long categoryId, Long accountId, String rawNote, Frequency frequency,
            Set<Integer> weeklyDays, Integer monthDay, boolean monthEnd, Integer yearMonth,
            Integer yearDay, LocalDate startDate, EndCondition endCondition, LocalDate untilDate,
            Integer countN) {

        // 归属先行落定：初始状态 ACTIVE、创建时间戳（需求 1.1）。归属确定后校验 / 装配下沉到共享逻辑。
        LocalDateTime now = LocalDateTime.now(clock);
        RecurringRule rule = new RecurringRule();
        rule.setUserId(userId);
        rule.setLedgerId(ledgerId);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(now);

        // 开始日期缺省取创建当日（Asia/Shanghai 口径，需求 1.5）。
        LocalDate effectiveStart = startDate != null ? startDate : LocalDate.now(clock);

        // 全部字段校验 + 装配（校验前置于任何写入，失败即零副作用；见 validateAndApply）。
        validateAndApply(rule, rawType, rawAmount, categoryId, accountId, rawNote, frequency,
                weeklyDays, monthDay, monthEnd, yearMonth, yearDay, effectiveStart, endCondition,
                untilDate, countN, now);
        return ruleRepository.save(rule);
    }

    // ---------------- 查询 / 编辑（tasks 3.2） ----------------

    /**
     * 列出当前账本当前用户的全部周期规则（含 {@link RuleStatus#ACTIVE} 与 {@link RuleStatus#PAUSED}），
     * 顺序由仓库提供（{@code created_at} 升序，需求 6.3、8.4）。仅返回归属当前用户且归属当前账本的规则，
     * 不含任何其它用户 / 其它账本的规则。
     *
     * @param userId   当前用户
     * @param ledgerId 当前账本 id
     * @return 归属当前用户 / 当前账本的规则列表（可能为空）
     */
    @Transactional(readOnly = true)
    public List<RecurringRule> list(Long userId, Long ledgerId) {
        return ruleRepository.findByUserIdAndLedgerIdOrderByCreatedAtAsc(userId, ledgerId);
    }

    /**
     * 读取规则详情（需求 6.7、8.4、8.5）。经 {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId}
     * 定位：不存在、不属于当前用户或不属于当前账本一律返回 {@code NOT_FOUND}，不泄漏他人 / 他账本规则的存在性。
     *
     * @param userId   当前用户
     * @param ledgerId 当前账本 id
     * @param ruleId   规则 id
     * @return 归属当前用户 / 当前账本的规则
     * @throws ApiException NOT_FOUND 当规则不存在或越权访问
     */
    @Transactional(readOnly = true)
    public RecurringRule get(Long userId, Long ledgerId, Long ruleId) {
        return ruleRepository.findByIdAndUserIdAndLedgerId(ruleId, userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("周期规则不存在"));
    }

    /**
     * 编辑一条周期规则的频率配置与记账模板字段（需求 6.3、6.4、6.7、8.5）。
     *
     * <p><b>归属与越权：</b>先经 {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId} 定位，
     * 不存在或越权（跨用户 / 跨账本）返回 {@code NOT_FOUND} 且零副作用。</p>
     *
     * <p><b>校验口径与创建完全一致：</b>复用 {@link #validateAndApply} 中同一组校验器
     * （类型 / 金额 / 分类 / 账户 / 备注、频率配置、结束条件），错误码与出错字段与创建路径逐一相同；
     * 任一校验失败即抛 {@link ApiException} 且不修改任何已持久化字段（校验全部前置于任何写入）。</p>
     *
     * <p><b>仅对之后新生成项生效（需求 6.3、6.4）：</b>本方法只更新规则行本身与 {@code updated_at}，
     * <b>不触碰</b> {@code recurring_pending_items}——既有 {@code PENDING} 项持有生成时的模板快照、
     * {@code CONFIRMED} 历史流水与 {@code SKIPPED} 记录均不受影响；编辑后的配置由懒生成在之后的新期次上生效。</p>
     *
     * <p>开始日期缺省时保留规则原开始日期（编辑不改变已确定的生效起点，除非显式传入新值）。
     * 状态（{@code ACTIVE}/{@code PAUSED}）与 {@code created_at} 不因编辑改变。</p>
     *
     * @param userId       当前用户（归属校验）
     * @param ledgerId     当前账本 id（账本隔离）
     * @param ruleId       待编辑规则 id
     * @param rawType      模板类型（{@code expense} / {@code income}）
     * @param rawAmount    模板金额（0.01–999,999,999.99，最多 2 位小数）
     * @param categoryId   模板分类 id（须属当前账本）
     * @param accountId    模板账户 id（须为当前用户在当前账本可用的账户）
     * @param rawNote      模板备注（≤200，可空）
     * @param frequency    频率节律（不可为 {@code null}）
     * @param weeklyDays   {@code WEEKLY} 星期几集合（1=周一..7=周日）；其余频率忽略
     * @param monthDay     {@code MONTHLY} 指定日（1–31）；{@code monthEnd=true} 或其余频率时可空
     * @param monthEnd     {@code MONTHLY}「月末」标记
     * @param yearMonth    {@code YEARLY} 指定月（1–12）；其余频率忽略
     * @param yearDay      {@code YEARLY} 指定日（1–31）；其余频率忽略
     * @param startDate    开始日期（{@code Asia/Shanghai} 自然日）；为空保留原开始日期
     * @param endCondition 结束条件（不可为 {@code null}）
     * @param untilDate    {@code UNTIL_DATE} 结束日期（不早于开始日期，含端点）
     * @param countN       {@code COUNT} 总期次数（1–9999）
     * @return 已更新的周期规则
     * @throws ApiException NOT_FOUND / RECURRING_RULE_INVALID / AMOUNT_INVALID / NOTE_TOO_LONG /
     *                      RECURRING_FREQUENCY_INVALID / RECURRING_END_CONDITION_INVALID
     */
    @Transactional
    public RecurringRule update(Long userId, Long ledgerId, Long ruleId, String rawType,
            BigDecimal rawAmount, Long categoryId, Long accountId, String rawNote,
            Frequency frequency, Set<Integer> weeklyDays, Integer monthDay, boolean monthEnd,
            Integer yearMonth, Integer yearDay, LocalDate startDate, EndCondition endCondition,
            LocalDate untilDate, Integer countN) {

        // 归属定位：不存在或越权（跨用户 / 跨账本）→ NOT_FOUND，零副作用（需求 6.7、8.5）。
        RecurringRule rule = ruleRepository.findByIdAndUserIdAndLedgerId(ruleId, userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("周期规则不存在"));

        // 开始日期缺省保留原值（编辑不隐式改动生效起点，需求 6.3）。
        LocalDate effectiveStart = startDate != null ? startDate : rule.getStartDate();

        // 复用与创建完全一致的校验 + 装配；校验前置于任何写入，失败即不改动任何字段（需求 6.4）。
        // 仅更新规则行本身，不触碰 recurring_pending_items——既有快照 / 历史流水不受影响（需求 6.3、6.4）。
        LocalDateTime now = LocalDateTime.now(clock);
        validateAndApply(rule, rawType, rawAmount, categoryId, accountId, rawNote, frequency,
                weeklyDays, monthDay, monthEnd, yearMonth, yearDay, effectiveStart, endCondition,
                untilDate, countN, now);
        return ruleRepository.save(rule);
    }

    // ---------------- 生命周期：暂停 / 恢复 / 删除（tasks 3.3） ----------------

    /**
     * 暂停一条规则（{@code ACTIVE}→{@code PAUSED}，需求 6.1、6.7、8.5）。
     *
     * <p><b>归属与越权：</b>先经 {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId} 定位，
     * 不存在或跨用户 / 跨账本一律返回 {@code NOT_FOUND} 且零副作用。</p>
     *
     * <p><b>只改规则状态，不触碰待确认项（需求 6.1）：</b>本方法仅将 {@code status} 置为
     * {@link RuleStatus#PAUSED} 并刷新 {@code updated_at}，<b>不触碰</b> {@code recurring_pending_items}——
     * 暂停前已生成的 {@code PENDING} 待确认项原样保留（仍可被确认或跳过）。暂停后该规则不进入懒生成扫描集合
     * （懒生成只扫 {@code ACTIVE} 规则），自暂停起不再生成新待确认项。</p>
     *
     * <p><b>历史不可变（需求 6.6）：</b>暂停不改动、不回滚任何已 {@code CONFIRMED} 历史流水、已发生的账户余额
     * 变动与已 {@code SKIPPED} 记录（本方法根本不涉及交易 / 账户 / 已处理项）。</p>
     *
     * <p>幂等：对已 {@code PAUSED} 的规则再次暂停不报错，结果仍为 {@code PAUSED}。</p>
     *
     * @param userId   当前用户（归属校验）
     * @param ledgerId 当前账本 id（账本隔离）
     * @param ruleId   待暂停规则 id
     * @return 已暂停的规则（{@code status=PAUSED}）
     * @throws ApiException NOT_FOUND 当规则不存在或越权
     */
    @Transactional
    public RecurringRule pause(Long userId, Long ledgerId, Long ruleId) {
        RecurringRule rule = ruleRepository.findByIdAndUserIdAndLedgerId(ruleId, userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("周期规则不存在"));
        rule.setStatus(RuleStatus.PAUSED);
        rule.setUpdatedAt(LocalDateTime.now(clock));
        return ruleRepository.save(rule);
    }

    /**
     * 恢复一条规则（{@code PAUSED}→{@code ACTIVE}，需求 6.2、6.7、8.5）。
     *
     * <p><b>归属与越权：</b>先经 {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId} 定位，
     * 不存在或跨用户 / 跨账本一律返回 {@code NOT_FOUND} 且零副作用。</p>
     *
     * <p><b>恢复后不回补暂停区间期次（需求 6.2）——生成下界（generationLowerBound）机制：</b>
     * 需求 6.2 要求恢复后<b>仅</b>为到期日在恢复当日（{@code Asia/Shanghai} 口径）或之后的期次生成待确认项，
     * <b>不补生成</b>到期日落在暂停区间内的期次。V38 表结构<b>刻意不设</b>专用的「生成下界」列（保持迁移纯增量、
     * 可整块摘除，需求 9.2），故本特性按 design.md「期次计算算法 / Glossary」约定的<b>免迁移派生机制</b>实现：
     * <ul>
     *   <li><b>恢复时以 {@code updated_at} 记录恢复当日</b>：本方法将 {@code updated_at} 置为恢复时刻
     *       （{@code LocalDateTime.now(clock)}，{@code Asia/Shanghai}），使其携带恢复日信息。</li>
     *   <li><b>懒生成（tasks 4.1）据此推进生成下界</b>：懒生成对 {@code ACTIVE} 规则以
     *       {@code generationLowerBound = max(startDate, updatedAt.toLocalDate())} 为扫描下界，
     *       跳过 {@code occurrenceDate < generationLowerBound} 的期次——从而恢复后暂停区间内（早于恢复当日）的
     *       期次因低于下界被跳过、不回补，而恢复当日及之后的期次照常补齐（需求 6.2）。</li>
     * </ul>
     * <b>task 4.1 契约：</b>懒生成<b>必须</b>以 {@code max(startDate, updatedAt.toLocalDate())} 作为每条
     * {@code ACTIVE} 规则的生成下界，方能兑现「恢复不回补」。</p>
     *
     * <p><b>MVP 取舍（供 task 4.1 知情）：</b>因复用 {@code updated_at} 而非专用列，创建 / 编辑同样会刷新
     * {@code updated_at}。实际使用中懒生成在每次打开视图时运行，到期期次通常已即时生成（已有记录则跳过），
     * 故此下界只影响「到期但从未被生成」的期次；对未指定 / 当日 / 未来开始日期的常规规则，
     * {@code max(startDate, updatedAt.toLocalDate())} 恒等于应有下界，需求 5（堆积补齐）与 3.7 不受影响。
     * 仅「开始日期早于创建当日的回填式规则」在此 MVP 下不回填创建日之前的期次——此为 design.md 明列的可接受简化，
     * 若日后需精确回填再引入专用列（届时才需迁移）。</p>
     *
     * <p><b>历史不可变（需求 6.6）：</b>恢复不改动、不回滚任何已 {@code CONFIRMED} 历史流水、账户余额与
     * {@code SKIPPED} 记录。幂等：对已 {@code ACTIVE} 的规则再次恢复不报错，结果仍为 {@code ACTIVE}
     * 且同样刷新 {@code updated_at}。</p>
     *
     * @param userId   当前用户（归属校验）
     * @param ledgerId 当前账本 id（账本隔离）
     * @param ruleId   待恢复规则 id
     * @return 已恢复的规则（{@code status=ACTIVE}，{@code updated_at} 为恢复当日）
     * @throws ApiException NOT_FOUND 当规则不存在或越权
     */
    @Transactional
    public RecurringRule resume(Long userId, Long ledgerId, Long ruleId) {
        RecurringRule rule = ruleRepository.findByIdAndUserIdAndLedgerId(ruleId, userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("周期规则不存在"));
        rule.setStatus(RuleStatus.ACTIVE);
        // 记录恢复当日（Asia/Shanghai）为生成下界锚点：懒生成据 max(startDate, updatedAt) 不回补暂停区间期次。
        rule.setUpdatedAt(LocalDateTime.now(clock));
        return ruleRepository.save(rule);
    }

    /**
     * 删除一条规则（需求 6.5、6.6、6.7、8.5）。
     *
     * <p><b>归属与越权：</b>先经 {@link RecurringRuleRepository#findByIdAndUserIdAndLedgerId} 定位，
     * 不存在或跨用户 / 跨账本一律返回 {@code NOT_FOUND} 且零副作用（不删任何待确认项、不删任何规则）。</p>
     *
     * <p><b>级联移除 PENDING、保留历史（需求 6.5）：</b>先经
     * {@link RecurringPendingItemRepository#deleteByRuleIdAndStatus} 级联移除该规则<b>全部尚未处理的
     * {@code PENDING}</b> 待确认项（使其不再出现在任何查询中），再删除规则行本身。该删除<b>只针对
     * {@code PENDING}</b>——已 {@code CONFIRMED} 的历史流水引用与已 {@code SKIPPED} 的期次记录一律保留
     * （它们承载真实入账 / 明确跳过语义，不随规则删除消失）。删除规则后该规则不再生成任何新待确认项。</p>
     *
     * <p><b>历史不可变（需求 6.6）：</b>删除不触碰 {@code transactions} / 账户余额，不改动、不回滚任何已
     * {@code CONFIRMED} 历史流水与已发生的账户余额变动；{@code CONFIRMED} / {@code SKIPPED} 生成项记录保留。
     * 待确认项表刻意不建指向 {@code recurring_rules} 的外键（需求 9.2），删除规则行不会连带删除其
     * {@code CONFIRMED} / {@code SKIPPED} 生成项。</p>
     *
     * @param userId   当前用户（归属校验）
     * @param ledgerId 当前账本 id（账本隔离）
     * @param ruleId   待删除规则 id
     * @throws ApiException NOT_FOUND 当规则不存在或越权
     */
    @Transactional
    public void delete(Long userId, Long ledgerId, Long ruleId) {
        RecurringRule rule = ruleRepository.findByIdAndUserIdAndLedgerId(ruleId, userId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("周期规则不存在"));
        // 级联移除全部 PENDING 待确认项（保留 CONFIRMED / SKIPPED，需求 6.5）。
        pendingItemRepository.deleteByRuleIdAndStatus(ruleId, PendingStatus.PENDING);
        // 删除规则行本身：此后不再生成任何新待确认项。
        ruleRepository.delete(rule);
    }

    /**
     * 校验并将模板字段 / 频率配置 / 结束条件装配到 {@code rule}（创建与编辑共用，保证两条路径校验口径、
     * 错误码与出错字段逐一一致）。
     *
     * <p><b>校验顺序前置于任何写入：</b>先按「类型 → 金额 → 备注 → 分类 → 账户 → 频率配置 → 结束条件」
     * 逐项校验（分类 / 账户按 {@code rule} 已确定的归属 {@code userId}/{@code ledgerId} 判定），任一失败即抛
     * {@link ApiException} 且此前未对 {@code rule} 做任何字段写入，从而拒绝即零副作用（需求 1.4、6.4）。</p>
     *
     * <p>调用方须在调用前确定 {@code rule} 的归属（{@code userId}/{@code ledgerId}）、状态与创建时间戳，
     * 并传入已计算好的 {@code effectiveStart}（创建取创建当日缺省、编辑保留原值）。本方法只负责校验、装配
     * 模板 / 频率 / 结束条件字段与 {@code updated_at}，不触碰归属 / 状态 / {@code created_at}。</p>
     */
    private void validateAndApply(RecurringRule rule, String rawType, BigDecimal rawAmount,
            Long categoryId, Long accountId, String rawNote, Frequency frequency,
            Set<Integer> weeklyDays, Integer monthDay, boolean monthEnd, Integer yearMonth,
            Integer yearDay, LocalDate effectiveStart, EndCondition endCondition,
            LocalDate untilDate, Integer countN, LocalDateTime now) {

        // 1) 模板字段校验（类型 / 金额 / 备注 / 分类 / 账户），全部前置于持久化（需求 1.2–1.4）。
        String type = validateType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        validateCategory(categoryId, rule.getLedgerId());
        validateAccount(rule.getUserId(), rule.getLedgerId(), accountId);

        // 2) 频率配置校验（需求 1.8、2.10）。
        String normalizedWeeklyDays = validateFrequency(frequency, weeklyDays, monthDay, monthEnd,
                yearMonth, yearDay);

        // 3) 结束条件校验（依赖开始日期，需求 1.6、1.7）。
        validateEndCondition(endCondition, effectiveStart, untilDate, countN);

        // 4) 装配（全部校验通过后才写入 rule）。
        rule.setType(type);
        rule.setAmount(amount);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote(note);
        rule.setFrequency(frequency);
        rule.setWeeklyDays(normalizedWeeklyDays);
        rule.setMonthDay(frequency == Frequency.MONTHLY && !monthEnd ? monthDay : null);
        rule.setMonthEnd(frequency == Frequency.MONTHLY && monthEnd);
        rule.setYearMonth(frequency == Frequency.YEARLY ? yearMonth : null);
        rule.setYearDay(frequency == Frequency.YEARLY ? yearDay : null);
        rule.setStartDate(effectiveStart);
        rule.setEndCondition(endCondition);
        rule.setUntilDate(endCondition == EndCondition.UNTIL_DATE ? untilDate : null);
        rule.setCountN(endCondition == EndCondition.COUNT ? countN : null);
        rule.setUpdatedAt(now);
    }

    // ---------------- 模板字段校验 ----------------

    /** 类型仅 {@code expense}/{@code income}（不含 transfer）；缺失或非法 → RECURRING_RULE_INVALID(type)。 */
    private String validateType(String rawType) {
        if (!TYPE_EXPENSE.equals(rawType) && !TYPE_INCOME.equals(rawType)) {
            throw ApiException.recurringRuleInvalid("type", "类型仅支持支出或收入");
        }
        return rawType;
    }

    /**
     * 金额校验：委托共享校验器 {@link RecurringTemplateValidator#validateAmount}（0.01–999,999,999.99、
     * 最多 2 位小数，缺失 / 越界 / 小数位超限复用 {@code AMOUNT_INVALID}）。与「修改后确认」口径一致
     * （需求 1.3、1.4、4.8）。
     */
    private BigDecimal validateAmount(BigDecimal rawAmount) {
        return templateValidator.validateAmount(rawAmount);
    }

    /**
     * 备注校验：委托共享校验器 {@link RecurringTemplateValidator#validateNote}（可空；非空 ≤200，超长复用
     * {@code NOTE_TOO_LONG}）。与「修改后确认」口径一致（需求 1.4、4.8）。
     */
    private String validateNote(String rawNote) {
        return templateValidator.validateNote(rawNote);
    }

    /** 分类校验：非空且属当前账本；缺失或不属当前账本 → RECURRING_RULE_INVALID(categoryId)（需求 1.4）。 */
    private void validateCategory(Long categoryId, Long ledgerId) {
        if (categoryId == null) {
            throw ApiException.recurringRuleInvalid("categoryId", "分类不存在或不属于当前账本");
        }
        categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                .orElseThrow(() -> ApiException.recurringRuleInvalid("categoryId", "分类不存在或不属于当前账本"));
    }

    /**
     * 账户校验：非空且为当前用户在当前账本可用的账户。复用 {@link LedgerAccountResolver#selectableAccounts}
     * （只读，不加锁）判定可用集合；不在集合内 → RECURRING_RULE_INVALID(accountId)（需求 1.4）。
     */
    private void validateAccount(Long userId, Long ledgerId, Long accountId) {
        if (accountId == null) {
            throw ApiException.recurringRuleInvalid("accountId", "账户不存在或在当前账本不可用");
        }
        List<Account> selectable = accountResolver.selectableAccounts(userId, ledgerId);
        boolean usable = selectable.stream().anyMatch(a -> a.getId().equals(accountId));
        if (!usable) {
            throw ApiException.recurringRuleInvalid("accountId", "账户不存在或在当前账本不可用");
        }
    }

    // ---------------- 频率配置校验 ----------------

    /**
     * 频率配置校验（需求 1.8、2.10）。返回 {@code WEEKLY} 规范化后的星期几逗号串（其余频率为 {@code null}）。
     *
     * <ul>
     *   <li>{@code frequency} 缺失 → 非法。</li>
     *   <li>{@code WEEKLY}：星期几集合非空且每个 ∈ 1–7；规范化为稳定升序逗号串（如 {@code "1,3,5"}）。</li>
     *   <li>{@code MONTHLY}：{@code monthEnd=true}，或 {@code monthDay} ∈ 1–31。</li>
     *   <li>{@code YEARLY}：{@code yearMonth} ∈ 1–12 且 {@code yearDay} ∈ 1–31。</li>
     *   <li>{@code DAILY}：无附加字段。</li>
     * </ul>
     */
    private String validateFrequency(Frequency frequency, Set<Integer> weeklyDays, Integer monthDay,
            boolean monthEnd, Integer yearMonth, Integer yearDay) {
        if (frequency == null) {
            throw ApiException.recurringFrequencyInvalid();
        }
        switch (frequency) {
            case DAILY -> {
                return null;
            }
            case WEEKLY -> {
                return normalizeWeeklyDays(weeklyDays);
            }
            case MONTHLY -> {
                if (!monthEnd) {
                    if (monthDay == null || monthDay < MONTH_DAY_MIN || monthDay > MONTH_DAY_MAX) {
                        throw ApiException.recurringFrequencyInvalid();
                    }
                }
                return null;
            }
            case YEARLY -> {
                if (yearMonth == null || yearMonth < YEAR_MONTH_MIN || yearMonth > YEAR_MONTH_MAX
                        || yearDay == null || yearDay < YEAR_DAY_MIN || yearDay > YEAR_DAY_MAX) {
                    throw ApiException.recurringFrequencyInvalid();
                }
                return null;
            }
            default -> throw ApiException.recurringFrequencyInvalid();
        }
    }

    /**
     * 校验并规范化 {@code WEEKLY} 星期几集合：非空且每个 ∈ 1–7，否则频率非法（需求 2.10）。
     * 规范化为按自然升序去重的逗号分隔串（如传入 {@code {5,1,3}} → {@code "1,3,5"}），保证存储稳定可复现。
     */
    private String normalizeWeeklyDays(Set<Integer> weeklyDays) {
        if (weeklyDays == null || weeklyDays.isEmpty()) {
            throw ApiException.recurringFrequencyInvalid();
        }
        TreeSet<Integer> ascending = new TreeSet<>();
        for (Integer day : weeklyDays) {
            if (day == null || day < WEEKDAY_MIN || day > WEEKDAY_MAX) {
                throw ApiException.recurringFrequencyInvalid();
            }
            ascending.add(day);
        }
        return ascending.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // ---------------- 结束条件校验 ----------------

    /**
     * 结束条件校验（需求 1.6、1.7）。
     *
     * <ul>
     *   <li>{@code endCondition} 缺失 → 非法。</li>
     *   <li>{@code NEVER}：无附加参数。</li>
     *   <li>{@code UNTIL_DATE}：{@code untilDate} 非空且不早于开始日期（含端点）。</li>
     *   <li>{@code COUNT}：{@code countN} 非空且 ∈ 1–9999。</li>
     * </ul>
     */
    private void validateEndCondition(EndCondition endCondition, LocalDate startDate,
            LocalDate untilDate, Integer countN) {
        if (endCondition == null) {
            throw ApiException.recurringEndConditionInvalid();
        }
        switch (endCondition) {
            case NEVER -> {
                // 无附加参数。
            }
            case UNTIL_DATE -> {
                if (untilDate == null || untilDate.isBefore(startDate)) {
                    throw ApiException.recurringEndConditionInvalid();
                }
            }
            case COUNT -> {
                if (countN == null || countN < COUNT_MIN || countN > COUNT_MAX) {
                    throw ApiException.recurringEndConditionInvalid();
                }
            }
            default -> throw ApiException.recurringEndConditionInvalid();
        }
    }
}
