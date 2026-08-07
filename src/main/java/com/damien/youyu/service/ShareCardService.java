package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.CategoryReportResponse;
import com.damien.youyu.api.dto.MonthlyDigestResponse;
import com.damien.youyu.api.dto.PersonalityTagsResponse;
import com.damien.youyu.api.dto.ShareCardResponse;
import com.damien.youyu.api.dto.ShareCardResponse.ShareCardCore;
import com.damien.youyu.api.dto.TrendReportResponse;
import com.damien.youyu.config.ShareCardProperties;
import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.domain.UserGrowth;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.service.AchievementSnapshotService.AchievementSnapshot;

/**
 * 分享卡片只读组合器（Share_Card_System，需求 1、2、9、10、12、13）。
 *
 * <p>本服务是既有分层（Controller → Service → Repository）之上的一层<b>只读组合器</b>：按卡片类型
 * {@link ShareCardType} 编排既有报表/预算聚合与成长域<b>只读判定件</b>，把该卡片的核心数据算出，交给
 * {@link ShareCardNarrator} 用中文模板渲染一句文案，与昵称/头像种子/标签/品牌名一起打包成
 * {@link ShareCardResponse}。</p>
 *
 * <p><b>严格只读、不触发结算（需求 10.1、13.1、13.6）</b>：{@link #card(Long, Long, ShareCardQuery)} 标注
 * {@code @Transactional(readOnly = true)}，全过程仅 SELECT。连续/成就/成长三类账本无关卡片<b>绝不调用会触发
 * 结算的 overview 方法</b>，改用只读判定件（{@link StreakJudgment} / {@link StreakMilestones} /
 * {@link GrowthLevelCurve} / {@link GrowthBadgeCatalog} / {@link AchievementSnapshotService}）与只读仓库读取，
 * 取到的是与源子系统 overview「先结算后读」同口径同值的<b>已持久化状态</b>；本服务<b>不新增任何 repository
 * 查询、不新增任何 SQL</b>（需求 13.1、13.2）。</p>
 *
 * <p><b>本类当前为骨架（任务 5）</b>：已搭建鉴权后编排入口、昵称/头像种子派生、品牌名恒在场字段与按
 * {@code cardType} 的分派 + 打包流程；6 类卡片核心数据的取数与可用条件评估器留待任务 6 实现，标签来源解析与
 * 隐私净化留待任务 7，文案接入留待任务 8。</p>
 */
@Service
public class ShareCardService {

    /** 昵称缺省展示名（需求 2.3，与 {@code pages/me/me.vue} 一致）。 */
    static final String DEFAULT_NICKNAME = "有余用户";

    // 账本相关卡片取数来源（需求 4、5、7）。
    private final MonthlyDigestService monthlyDigestService;   // MONTHLY_SUMMARY
    private final ReportService reportService;                 // ANNUAL_BILL（trend/category/monthly）
    private final BudgetService budgetService;                 // BUDGET_ACHIEVED

    // 成长域只读判定件（需求 3、6、8；只读、不结算）。
    private final UserGrowthRepository userGrowthRepository;   // STREAK / LEVEL 只读档案
    private final StreakMilestones streakMilestones;           // 里程碑集合（只读，不写死数值）
    private final GrowthLevelCurve growthLevelCurve;           // 等级曲线（只读）
    private final AchievementSnapshotService achievementSnapshotService; // 成就快照（只读，不结算）
    private final GrowthBadgeCatalog badgeCatalog;             // 成就清单（只读）

    // 标签来源、昵称/头像种子、配置与文案渲染。
    private final PersonalityTagService personalityTagService; // 标签来源（账本相关卡片，可省）
    private final UserRepository userRepository;               // 昵称/头像种子
    private final ShareCardProperties props;                   // 品牌名/Logo 比例
    private final ShareCardNarrator narrator;                  // 中文模板渲染
    private final Clock clock;                                 // Asia/Shanghai 月/年状态 + 缺省周期

    public ShareCardService(
            MonthlyDigestService monthlyDigestService,
            ReportService reportService,
            BudgetService budgetService,
            UserGrowthRepository userGrowthRepository,
            StreakMilestones streakMilestones,
            GrowthLevelCurve growthLevelCurve,
            AchievementSnapshotService achievementSnapshotService,
            GrowthBadgeCatalog badgeCatalog,
            PersonalityTagService personalityTagService,
            UserRepository userRepository,
            ShareCardProperties props,
            ShareCardNarrator narrator,
            Clock clock) {
        this.monthlyDigestService = monthlyDigestService;
        this.reportService = reportService;
        this.budgetService = budgetService;
        this.userGrowthRepository = userGrowthRepository;
        this.streakMilestones = streakMilestones;
        this.growthLevelCurve = growthLevelCurve;
        this.achievementSnapshotService = achievementSnapshotService;
        this.badgeCatalog = badgeCatalog;
        this.personalityTagService = personalityTagService;
        this.userRepository = userRepository;
        this.props = props;
        this.narrator = narrator;
        this.clock = clock;
    }

    /**
     * 按卡片类型打包该用户/账本上下文下的分享卡片数据包（需求 1.2、1.3、2.2、2.3、10.1）。全过程仅 SELECT、
     * 不触发结算、不落库（需求 13.1、13.6）。
     *
     * <p>编排步骤（骨架）：① 派生昵称与文字头像种子（需求 2.2、2.3）；② 按 {@code query.cardType()} 分派到
     * 各卡片评估器，得核心数据 {@code core}（可用时非空）与 {@code available}/{@code unavailableReason}
     * （评估器细节留待任务 6）；③ 卡片可用时以 {@link ShareCardNarrator} 渲染一句文案（任务 8 完善兜底）；
     * ④ 组装 {@link ShareCardResponse}，使 {@code cardType}/{@code available}/{@code nickname}/
     * {@code avatarSeed}/{@code brandName} 恒在场（标签来源与隐私净化留待任务 7）。</p>
     *
     * @param userId   当前登录用户 id（已由控制器完成鉴权 + 存在校验，需求 10.3）
     * @param ledgerId 当前账本 id（账本相关卡片非空，账本无关卡片为 {@code null}，需求 1.7、1.8）
     * @param query    按卡片类型解析后的周期/标识载体（恒非空）
     * @return 该卡片的数据包（可用/不可用两态）
     */
    @Transactional(readOnly = true)
    public ShareCardResponse card(Long userId, Long ledgerId, ShareCardQuery query) {
        ShareCardType cardType = query.cardType();

        // ① 昵称与文字头像种子（需求 2.2、2.3）。
        String nickname = resolveNickname(userId);
        String avatarSeed = resolveAvatarSeed(nickname);

        // ② 品牌名恒在场（需求 1.2）。
        String brandName = props.brandNameOrDefault();

        // ③ 按 cardType 分派到各卡片评估器（任务 6 实现），得核心数据与可用性。
        Evaluation evaluation = dispatch(userId, ledgerId, query);

        // ④ 卡片可用时渲染一句 AI 文案（需求 9.1）；卡片不可用时 narrative=null（需求 9.1）。
        //    narrator.render 内部已对关键数值缺失做「该类型内置默认文案」兜底，核心数据存在时恒返回非空、非空白
        //    文案、绝不抛错（需求 9.7），故可用卡片的 narrative 恒满足 available == (narrative != null) 不变式，
        //    兜底逻辑集中在 narrator 内、此处不重复。此处再加一层防御式保障：万一 render 对可用卡片返回了
        //    null/空白（当前实现不会发生），退化为 narrator 自身的内置默认文案（render(cardType, null) 直接走
        //    默认文案分支），确保「卡片可用 ⟹ narrative 非空」始终成立（需求 9.1、9.7）。
        String narrative = null;
        if (evaluation.available()) {
            narrative = narrator.render(cardType, evaluation.core());
            if (narrative == null || narrative.isBlank()) {
                narrative = narrator.render(cardType, null);
            }
        }

        // ⑤ 标签来源解析（可省，无来源 → null，绝不阻断出卡；需求 2.4）。卡片不可用时无标签。
        String label = evaluation.available()
                ? resolveLabel(userId, ledgerId, cardType, query, evaluation.core())
                : null;

        // ⑥ 组装数据包后做防御式隐私净化，确保不含任何被禁字段（需求 12.3、12.4、12.5）。
        ShareCardResponse response = new ShareCardResponse(
                cardType.name(),
                evaluation.available(),
                evaluation.unavailableReason(),
                nickname,
                avatarSeed,
                label,
                narrative,
                brandName,
                evaluation.core());
        return sanitize(response);
    }

    // ---------------- 标签来源解析（可省，需求 2.4） ----------------

    /**
     * 解析卡片标签（一枚简短标签，锦上添花），无可用来源时返回 {@code null} 而非阻断出卡（需求 2.4）。
     * 标签来源随卡片类型不同：
     *
     * <ul>
     *   <li><b>账本相关卡片</b>（{@code MONTHLY_SUMMARY} / {@code ANNUAL_BILL} / {@code BUDGET_ACHIEVED}）：
     *       复用 {@link PersonalityTagService#tags(Long, YearMonth)} 的<b>首枚</b>趣味人格标签标题
     *       （同口径、只读）；无标签或处于兜底态 → {@code null}。年度账单以「当年当月 / 目标年 12 月」这一
     *       合理自然月取标签（见 {@link #annualLabelMonth(int)}）。</li>
     *   <li><b>账本无关坚持/升级卡</b>（{@code STREAK_MILESTONE} / {@code LEVEL_UP}）：取成就快照中
     *       <b>最近解锁</b>成就的展示名称为标签；无任何已解锁成就 → {@code null}。</li>
     *   <li><b>获得徽章卡</b>（{@code ACHIEVEMENT_BADGE}）：取该徽章所属<b>分类的中文名</b>
     *       （{@link AchievementCategory#label()}，如「坚持」「积累」），与核心的徽章名区分开；无则 {@code null}。</li>
     * </ul>
     *
     * <p>标签来源均为既有只读口径，<b>不新增任何查询</b>；任何解析失败（含依赖抛错）一律降级为
     * {@code null}，绝不使卡片出图失败（需求 2.4）。</p>
     */
    private String resolveLabel(Long userId, Long ledgerId, ShareCardType cardType,
                                ShareCardQuery query, ShareCardCore core) {
        try {
            return switch (cardType) {
                case MONTHLY_SUMMARY, BUDGET_ACHIEVED -> firstPersonalityTagTitle(ledgerId, query.month());
                case ANNUAL_BILL -> firstPersonalityTagTitle(ledgerId, annualLabelMonth(query.year()));
                case STREAK_MILESTONE, LEVEL_UP -> latestUnlockedBadgeName(userId);
                case ACHIEVEMENT_BADGE -> badgeCategoryLabel(core);
            };
        } catch (RuntimeException ex) {
            // 解析失败一律降级为 null，绝不阻断出卡（需求 2.4）。
            return null;
        }
    }

    /**
     * 账本相关卡片标签：取趣味人格标签首枚标题（同口径只读）。兜底态或无标签 → {@code null}（需求 2.4）。
     */
    private String firstPersonalityTagTitle(Long ledgerId, YearMonth month) {
        PersonalityTagsResponse tags = personalityTagService.tags(ledgerId, month);
        if (tags == null || tags.tags() == null || tags.tags().isEmpty()) {
            return null;
        }
        String title = tags.tags().get(0).title();
        return (title != null && !title.isBlank()) ? title : null;
    }

    /**
     * 年度账单卡取人格标签所用的自然月：目标年即当前自然年时取「当年当月」，否则取该年 12 月（一个合理的
     * 月度上下文）。均按 {@code Asia/Shanghai} 时钟（需求 2.4，标签来源可省，取值只影响标签、不影响核心数据）。
     */
    private YearMonth annualLabelMonth(int year) {
        YearMonth now = YearMonth.now(clock);
        return year == now.getYear() ? now : YearMonth.of(year, 12);
    }

    /**
     * 账本无关坚持/升级卡标签：取成就快照中最近解锁成就的展示名称（只读、不结算）。无已解锁成就 →
     * {@code null}（需求 2.4）。最近解锁的选取与 {@link #evaluateAchievementBadge} 缺省选取同一判定
     * （{@link #isMoreRecentUnlock}），确定可复现。
     */
    private String latestUnlockedBadgeName(Long userId) {
        AchievementSnapshot snapshot = achievementSnapshotService.snapshot(userId);
        Map.Entry<String, GrowthEvent> latest = null;
        for (Map.Entry<String, GrowthEvent> entry : snapshot.unlockedByCode().entrySet()) {
            if (latest == null || isMoreRecentUnlock(entry.getValue(), latest.getValue())) {
                latest = entry;
            }
        }
        return latest != null ? badgeNameOf(latest.getKey()) : null;
    }

    /** 由成就编码取其展示名称（成就清单常量）；不在清单内时为 {@code null}。 */
    private String badgeNameOf(String code) {
        return badgeCatalog.badges().stream()
                .filter(badge -> badge.code().equals(code))
                .map(BadgeDef::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获得徽章卡标签：取核心徽章所属分类的中文名（{@link AchievementCategory#label()}）。以核心的
     * 展示名称回查成就清单定位其分类（成就名称在清单内唯一），无匹配 → {@code null}（需求 2.4）。
     */
    private String badgeCategoryLabel(ShareCardCore core) {
        if (core == null || core.badgeName() == null) {
            return null;
        }
        return badgeCatalog.badges().stream()
                .filter(badge -> badge.name().equals(core.badgeName()))
                .map(badge -> badge.category().label())
                .findFirst()
                .orElse(null);
    }

    // ---------------- 隐私净化（防御式，需求 12.3、12.4、12.5） ----------------

    /** 邮箱样式（本地部分 @ 域名）——被禁字段之一（需求 12.3）。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 令牌样式：JWT（三段以点分隔的 base64url）或较长的连续 base64url/十六进制串——覆盖任何访问/刷新令牌
     * 内容（需求 12.3）。合法中文文案（≤60 字符、含中文与标点）不会出现 32+ 连续的 ASCII 字母数字串，故不误伤。
     */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("(eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,})|([A-Za-z0-9_-]{32,})");

    /**
     * 防御式隐私净化（需求 12.3、12.4、12.5）。
     *
     * <p>{@link ShareCardResponse} 的<b>字段集本身即隐私白名单</b>：record 的字段在编译期固定，从结构上
     * 就不可能承载 email / 任何令牌 / {@code plan} / {@code wx_openid} / {@code wx_unionid} / 邀请码 /
     * 其它账本数据 / {@code external_id} / 原始备注全文 / 商户原始标识——因此本步骤主要是对少数<b>自由文本
     * 字段</b>（{@code nickname} / {@code avatarSeed} / {@code label} / {@code narrative}）做一次防御式扫描，
     * 确保它们不会意外携带上述被禁内容（例如某用户把邮箱当昵称）。</p>
     *
     * <p>净化规则（需求 12.5：检测到即移除、照常返回其余合法字段、不改合法取值、不中断请求）：</p>
     * <ul>
     *   <li>{@code label} / {@code narrative} 命中禁用模式 → 置 {@code null}（移除该字段，其余字段照常）；</li>
     *   <li>{@code nickname} 命中禁用模式 → 回退默认展示名「有余用户」（该字段为用户自身标识、须恒在场，
     *       故以默认值替代而非置空，仍达成「不下发被禁内容」，需求 2.3、12.5）；</li>
     *   <li>{@code avatarSeed} 命中禁用模式 → 由净化后的昵称首字符重算（单字符，结构上几乎不可能命中）。</li>
     * </ul>
     *
     * <p>未命中任何被禁内容时原样返回同一实例，绝不改动任何合法字段取值（需求 12.5）。</p>
     */
    private ShareCardResponse sanitize(ShareCardResponse r) {
        String nickname = containsForbiddenContent(r.nickname()) ? DEFAULT_NICKNAME : r.nickname();
        String avatarSeed = containsForbiddenContent(r.avatarSeed())
                ? resolveAvatarSeed(nickname)
                : r.avatarSeed();
        String label = containsForbiddenContent(r.label()) ? null : r.label();
        String narrative = containsForbiddenContent(r.narrative()) ? null : r.narrative();

        // 未命中任何被禁内容 → 原样返回，不改动任何合法字段取值（需求 12.5）。
        if (Objects.equals(nickname, r.nickname())
                && Objects.equals(avatarSeed, r.avatarSeed())
                && Objects.equals(label, r.label())
                && Objects.equals(narrative, r.narrative())) {
            return r;
        }
        return new ShareCardResponse(
                r.cardType(), r.available(), r.unavailableReason(),
                nickname, avatarSeed, label, narrative, r.brandName(), r.core());
    }

    /** 自由文本是否含被禁内容（email 样式或令牌样式）。{@code null}/空白视为不含（需求 12.3、12.5）。 */
    private static boolean containsForbiddenContent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(text).find() || TOKEN_PATTERN.matcher(text).find();
    }

    // ---------------- 昵称与文字头像种子（需求 2.2、2.3） ----------------

    /** 昵称：读当前用户 {@code nickname}，去首尾空白后非空取原值、否则取默认展示名「有余用户」（需求 2.3）。 */
    private String resolveNickname(Long userId) {
        String raw = userRepository.findById(userId).map(User::getNickname).orElse(null);
        if (raw != null) {
            String trimmed = raw.strip();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return DEFAULT_NICKNAME;
    }

    /**
     * 文字头像种子：昵称首个 Unicode 码点（与 {@code pages/me/me.vue} 的 {@code nickname.slice(0,1)} 同口径，
     * 需求 2.2）。不引入头像图片上传/外链。入参昵称恒非空（见 {@link #resolveNickname(Long)}）。
     */
    private static String resolveAvatarSeed(String nickname) {
        int firstCodePoint = nickname.codePointAt(0);
        return new String(Character.toChars(firstCodePoint));
    }

    // ---------------- 按 cardType 分派（评估器留待任务 6 实现） ----------------

    /** 按卡片类型分派到各评估器（需求 1.2）。 */
    private Evaluation dispatch(Long userId, Long ledgerId, ShareCardQuery query) {
        return switch (query.cardType()) {
            case STREAK_MILESTONE -> evaluateStreakMilestone(userId, query);
            case MONTHLY_SUMMARY -> evaluateMonthlySummary(ledgerId, query);
            case ANNUAL_BILL -> evaluateAnnualBill(ledgerId, query);
            case ACHIEVEMENT_BADGE -> evaluateAchievementBadge(userId, query);
            case BUDGET_ACHIEVED -> evaluateBudgetAchieved(ledgerId, query);
            case LEVEL_UP -> evaluateLevelUp(userId, query);
        };
    }

    /**
     * STREAK_MILESTONE（连续记账里程碑，账本无关；需求 3）评估器——<b>只读、不触发结算</b>。
     *
     * <p>取数（需求 1.10、3.1）：以 {@code userGrowthRepository.findById}（<b>非</b> {@code findForUpdateById}，
     * 不加写锁、不结算）读取已持久化成长档案；历史最长连续天数取 {@code maxStreakDays}；当前连续天数经
     * {@link StreakJudgment#currentStreakDays} 按 {@code Asia/Shanghai} 判定日换算——与 {@code StreakQueryService}
     * 读取侧同一实现同值。里程碑集合取 {@link StreakMilestones#thresholds()}（派生自成就清单 {@code MAX_STREAK}
     * 门槛，<b>不在 share-card 侧写死</b>，需求 3.1）。</p>
     *
     * <p>门控与算术：已达成里程碑 = 集合中 ≤ {@code maxStreakDays} 的取值；核心里程碑 = 已达成里程碑的最大取值
     * （需求 3.2）。可用当且仅当存在至少一个已达成里程碑，携带 {@code milestone}（核心里程碑）、
     * {@code currentStreakDays}、{@code maxStreakDays}（需求 3.3）；否则不可用（{@code NO_MILESTONE_ACHIEVED}），
     * 不抛错（需求 3.4）。{@code milestone} 参数属于已达成里程碑则以其为核心里程碑，未达成或不属集合则回退
     * 核心里程碑（需求 3.5）。</p>
     */
    private Evaluation evaluateStreakMilestone(Long userId, ShareCardQuery query) {
        // 只读读取成长档案（findById 不加写锁、不结算，需求 13.1）。
        UserGrowth profile = userGrowthRepository.findById(userId).orElse(null);
        int maxStreakDays = profile != null ? profile.getMaxStreakDays() : 0;
        LocalDate lastRecordDate = profile != null ? profile.getLastRecordDate() : null;
        int currentSegmentDays = profile != null ? profile.getCurrentStreakDays() : 0;
        int currentStreak = StreakJudgment.currentStreakDays(
                lastRecordDate, currentSegmentDays, LocalDate.now(clock));

        // 已达成里程碑 = 集合中 ≤ maxStreakDays 的取值（集合升序，末位为最大，需求 3.2）。
        List<Integer> achieved = streakMilestones.thresholds().stream()
                .filter(threshold -> threshold <= maxStreakDays)
                .toList();
        if (achieved.isEmpty()) {
            return Evaluation.unavailable("NO_MILESTONE_ACHIEVED"); // 需求 3.4
        }
        int coreMilestone = achieved.get(achieved.size() - 1); // 已达成里程碑的最大取值（需求 3.2）

        // milestone 参数：属于已达成里程碑则以其为核心里程碑，否则回退核心里程碑（需求 3.5）。
        Integer requested = query.milestone();
        int milestone = (requested != null && achieved.contains(requested)) ? requested : coreMilestone;

        ShareCardCore core = new ShareCardCore(
                milestone, currentStreak, maxStreakDays,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null);
        return Evaluation.available(core);
    }

    /**
     * MONTHLY_SUMMARY（本月总结，账本相关；需求 4）评估器——<b>只读派生，复用月报数据包</b>。
     *
     * <p>取数（需求 4.1）：以 {@link MonthlyDigestService#digest(Long, YearMonth)} 复用智能月报数据包，
     * 同口径、按 {@code Asia/Shanghai} 边界、排除 {@code type=transfer}、金额 2dp HALF_UP。核心数据携带
     * {@code month}（{@code YYYY-MM}）、{@code monthStatus}、{@code income}、{@code expense}、{@code balance}
     * （={@code digest.netBalance()}）、{@code topCategoryName}/{@code topCategoryPercent}（取
     * {@code digest.categoryRanking()} 首项，可空）。</p>
     *
     * <p>月状态（需求 4.3）：{@code digest.monthStatus()} 即
     * {@code month.isBefore(YearMonth.now(clock)) ? "final" : "partial"}，{@code partial} 月核心数据基于截至
     * 当前时刻的数据（与月报同口径）。门控（需求 4.4、4.5）：可用当且仅当目标月存在至少一笔计入统计的交易，
     * 即 {@code income > 0 或 expense > 0}（转账已排除）；否则不可用（{@code NO_TRANSACTIONS}），不抛错。</p>
     */
    private Evaluation evaluateMonthlySummary(Long ledgerId, ShareCardQuery query) {
        // 复用智能月报数据包（同口径、Asia/Shanghai、排除 transfer、金额 2dp HALF_UP，需求 4.1、4.3）。
        MonthlyDigestResponse digest = monthlyDigestService.digest(ledgerId, query.month());
        BigDecimal income = digest.income();
        BigDecimal expense = digest.expense();

        // 门控：目标月存在至少一笔计入统计的交易（income > 0 或 expense > 0，需求 4.4、4.5）。
        boolean hasTransactions = income.compareTo(BigDecimal.ZERO) > 0
                || expense.compareTo(BigDecimal.ZERO) > 0;
        if (!hasTransactions) {
            return Evaluation.unavailable("NO_TRANSACTIONS"); // 需求 4.5
        }

        // 支出占比最高分类：取分类排行首项（可空，需求 4.1）。
        String topCategoryName = null;
        BigDecimal topCategoryPercent = null;
        List<CategoryReportResponse.CategoryShare> ranking = digest.categoryRanking();
        if (ranking != null && !ranking.isEmpty()) {
            CategoryReportResponse.CategoryShare top = ranking.get(0);
            topCategoryName = top.categoryName();
            topCategoryPercent = top.percentage();
        }

        ShareCardCore core = new ShareCardCore(
                null, null, null,
                digest.month(), digest.monthStatus(), income, expense, digest.netBalance(),
                topCategoryName, topCategoryPercent,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null);
        return Evaluation.available(core);
    }

    /**
     * ANNUAL_BILL（年度账单，账本相关；需求 5）评估器——<b>只读派生，复用报表按自然年汇总</b>。
     *
     * <p>取数（需求 5.1）：以 {@link ReportService#trendReport(Long, YearMonth, YearMonth)} 取目标年 1–12 月
     * 12 个月点（同口径、按 {@code Asia/Shanghai} 边界、排除 {@code type=transfer}、金额 2dp HALF_UP），
     * {@code annualIncome}/{@code annualExpense} 为各月点收入/支出之和、{@code annualBalance} = 收入 − 支出、
     * {@code topExpenseMonth} 为 expense 最大的月点（并列取月份小者，{@code YYYY-MM}）。以
     * {@link ReportService#categoryReport(Long, LocalDate, LocalDate, TransactionType)}（目标年 01-01..12-31，
     * {@link TransactionType#EXPENSE}）首项分类名为 {@code topCategoryName}（可空）。一年 12 个月 &lt;
     * {@code trendReport} 的 24 月上限，不触发 {@code REPORT_RANGE_INVALID}。</p>
     *
     * <p>年状态（需求 5.3）：{@code yearStatus = year < currentYear ? "final" : "partial"}，其中
     * {@code currentYear} 取 {@code Asia/Shanghai} 当前自然年（与 {@link ShareCardQuery} 缺省口径一致）。
     * 门控（需求 5.4、5.5）：可用当且仅当目标年存在至少一笔计入统计的交易，即
     * {@code annualIncome > 0 或 annualExpense > 0}（转账已排除）；否则不可用（{@code NO_TRANSACTIONS}），
     * 不抛错。核心数据携带 {@code year}（{@code YYYY}）、{@code yearStatus}、{@code annualIncome}、
     * {@code annualExpense}、{@code annualBalance}（+ 可空 {@code topExpenseMonth}/{@code topCategoryName}）。</p>
     */
    private Evaluation evaluateAnnualBill(Long ledgerId, ShareCardQuery query) {
        int year = query.year();

        // 目标年 1–12 月 12 个月点（同口径、Asia/Shanghai、排除 transfer、金额 2dp，需求 5.1）。
        TrendReportResponse trend = reportService.trendReport(
                ledgerId, YearMonth.of(year, 1), YearMonth.of(year, 12));

        BigDecimal annualIncome = BigDecimal.ZERO;
        BigDecimal annualExpense = BigDecimal.ZERO;
        String topExpenseMonth = null;
        BigDecimal topExpenseAmount = null;
        for (TrendReportResponse.MonthPoint point : trend.months()) {
            annualIncome = annualIncome.add(point.income());
            annualExpense = annualExpense.add(point.expense());
            // 支出最高月：严格大于才替换 → 月点升序遍历，并列自然取月份小者（需求 5.1）。
            if (topExpenseAmount == null || point.expense().compareTo(topExpenseAmount) > 0) {
                topExpenseAmount = point.expense();
                topExpenseMonth = point.month();
            }
        }
        annualIncome = scale(annualIncome);
        annualExpense = scale(annualExpense);
        BigDecimal annualBalance = scale(annualIncome.subtract(annualExpense));

        // 门控：目标年存在至少一笔计入统计的交易（annualIncome > 0 或 annualExpense > 0，需求 5.4、5.5）。
        boolean hasTransactions = annualIncome.compareTo(BigDecimal.ZERO) > 0
                || annualExpense.compareTo(BigDecimal.ZERO) > 0;
        if (!hasTransactions) {
            return Evaluation.unavailable("NO_TRANSACTIONS"); // 需求 5.5
        }

        // 全年无支出时 topExpenseMonth 无意义，置 null（可空字段，需求 5.1）。
        if (annualExpense.compareTo(BigDecimal.ZERO) == 0) {
            topExpenseMonth = null;
        }

        // 支出占比最高分类：取分类占比报表首项（可空，需求 5.1）。
        String topCategoryName = null;
        CategoryReportResponse categoryReport = reportService.categoryReport(
                ledgerId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                TransactionType.EXPENSE);
        List<CategoryReportResponse.CategoryShare> categories = categoryReport.categories();
        if (categories != null && !categories.isEmpty()) {
            topCategoryName = categories.get(0).categoryName();
        }

        // 年状态：目标年早于当前自然年为 final，否则 partial（需求 5.3）。
        int currentYear = LocalDate.now(clock).getYear();
        String yearStatus = year < currentYear ? "final" : "partial";

        ShareCardCore core = new ShareCardCore(
                null, null, null,
                null, null, null, null, null, null, null,
                String.valueOf(year), yearStatus, annualIncome, annualExpense, annualBalance,
                topExpenseMonth,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null);
        return Evaluation.available(core);
    }

    /** 金额统一保留 2 位小数 HALF_UP（需求 1.4、5.1）。 */
    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 解锁日期格式：{@code YYYY-MM-DD}（需求 6.2）。 */
    private static final DateTimeFormatter UNLOCKED_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * ACHIEVEMENT_BADGE（获得徽章，账本无关；需求 6）评估器——<b>只读、不触发结算</b>。
     *
     * <p>取数（需求 1.10、6.1）：以 {@link AchievementSnapshotService#snapshot(Long)}（内部仅 SELECT、
     * 不结算）取该用户成就事实快照，其 {@code unlockedByCode()} 即已解锁成就编码 →
     * 对应 {@code BADGE} 事件行（承载解锁时刻 {@code created_at}）的映射，仅含成就清单内的编码；配
     * {@link GrowthBadgeCatalog#badges()} 取该成就的展示名称与中文描述。<b>仅以已解锁成就为候选</b>。</p>
     *
     * <p>门控与选取：{@code code} 参数命中清单且已解锁 → 该成就为核心成就（需求 6.2）；否则（不属清单或
     * 尚未解锁）不可用（{@code BADGE_NOT_UNLOCKED}），不抛错（需求 6.3）——{@link AchievementSnapshot#unlocked(String)}
     * 对未知编码亦返回 {@code false}，两种情形一并覆盖。{@code code} 缺省 → 取<b>解锁时刻最新</b>的一枚已解锁
     * 成就（按对应 {@code BADGE} 事件 {@code created_at}，并列时取事件 id 较大者以保确定性，需求 1.6、6.4）；
     * 无任何已解锁成就 → 不可用（{@code NO_UNLOCKED_ACHIEVEMENT}），不抛错（需求 6.4）。</p>
     *
     * <p>核心数据仅携带展示名称（{@code badgeName}）、中文描述（{@code badgeDescription}）、精确到自然日的
     * 解锁日期（{@code unlockedDate}，{@code YYYY-MM-DD}）；<b>不下发成就编码原始字面量之外用户不可读的内部
     * 标识</b>（需求 6.2、6.6）。解锁日期由 {@code BADGE} 事件 {@code created_at}（以 {@code Asia/Shanghai}
     * 时钟写入的 {@link LocalDateTime}）取自然日得到，与成就清单接口解锁时刻同源。</p>
     */
    private Evaluation evaluateAchievementBadge(Long userId, ShareCardQuery query) {
        // 只读取成就事实快照（snapshot 内部仅 SELECT、不结算，需求 6.1、13.1）。
        AchievementSnapshot snapshot = achievementSnapshotService.snapshot(userId);

        String code = query.code();
        if (code != null) {
            // 指定编码：命中清单且已解锁才可用；未知编码或未解锁一律不可用、不抛错（需求 6.2、6.3）。
            if (!snapshot.unlocked(code)) {
                return Evaluation.unavailable("BADGE_NOT_UNLOCKED"); // 需求 6.3
            }
            return badgeEvaluation(code, snapshot.eventOf(code));
        }

        // 缺省：取解锁时刻最新的一枚已解锁成就（需求 6.4）。
        Map.Entry<String, GrowthEvent> latest = null;
        for (Map.Entry<String, GrowthEvent> entry : snapshot.unlockedByCode().entrySet()) {
            if (latest == null || isMoreRecentUnlock(entry.getValue(), latest.getValue())) {
                latest = entry;
            }
        }
        if (latest == null) {
            return Evaluation.unavailable("NO_UNLOCKED_ACHIEVEMENT"); // 需求 6.4
        }
        return badgeEvaluation(latest.getKey(), latest.getValue());
    }

    /**
     * 由已解锁成就编码 + 其 {@code BADGE} 事件行组装核心数据：仅携带展示名称、中文描述与解锁日期
     * （需求 6.2、6.6）。展示名称/描述取自成就清单常量，解锁日期取事件 {@code created_at} 的自然日。
     * 编码不在清单内（理论上不会发生，已解锁映射只含清单内编码）时按不可用兜底、不抛错。
     */
    private Evaluation badgeEvaluation(String code, GrowthEvent event) {
        BadgeDef def = badgeCatalog.badges().stream()
                .filter(badge -> badge.code().equals(code))
                .findFirst()
                .orElse(null);
        if (def == null || event == null) {
            return Evaluation.unavailable("BADGE_NOT_UNLOCKED");
        }
        String unlockedDate = event.getCreatedAt() != null
                ? event.getCreatedAt().toLocalDate().format(UNLOCKED_DATE_FORMAT)
                : null;

        ShareCardCore core = new ShareCardCore(
                null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                def.name(), def.description(), unlockedDate,
                null, null, null, null, null,
                null, null, null, null, null, null);
        return Evaluation.available(core);
    }

    /**
     * 比较两枚已解锁成就的解锁先后：主键为 {@code BADGE} 事件 {@code created_at}，并列时以事件 id 较大者
     * 视为更新，保证缺省选取确定可复现（需求 1.6、6.4）。
     */
    private static boolean isMoreRecentUnlock(GrowthEvent candidate, GrowthEvent current) {
        LocalDateTime candidateAt = candidate.getCreatedAt();
        LocalDateTime currentAt = current.getCreatedAt();
        if (candidateAt == null || currentAt == null) {
            // 缺失解锁时刻退化为按事件 id 比较，仍保确定性。
            return idOf(candidate) > idOf(current);
        }
        int cmp = candidateAt.compareTo(currentAt);
        if (cmp != 0) {
            return cmp > 0;
        }
        return idOf(candidate) > idOf(current);
    }

    /** {@code null} 安全的事件 id 取值，仅用于最近解锁的并列择一。 */
    private static long idOf(GrowthEvent event) {
        return event.getId() == null ? Long.MIN_VALUE : event.getId();
    }

    /**
     * BUDGET_ACHIEVED（预算达成，账本相关；需求 7）评估器——<b>只读派生，复用预算总览口径</b>。
     *
     * <p>取数（需求 7.1、7.2）：以 {@link BudgetService#overview(Long, YearMonth)} 复用预算总览口径，
     * 同口径、按 {@code Asia/Shanghai} 自然月边界、支出统计排除 {@code type=transfer}、金额 2dp HALF_UP。
     * 核心数据携带 {@code month}（{@code YYYY-MM}，取 {@code ov.month()}）、{@code totalBudget}、
     * {@code usedAmount}（={@code ov.spent()}）、{@code remaining}（={@code ov.remaining()}）、
     * {@code usedPercent}（据同一 {@code spent}/{@code totalBudget} 以 {@code spent × 100 ÷ totalBudget}
     * 计算，2dp HALF_UP，与 fun-personality-tags 预算大师同法满足 2dp）、{@code budgetStatus}
     * （{@code ov.status()}，OK/WARN/OVER）。因 {@link BudgetOverviewResponse#usedPercent()} 为整数，故此处
     * 由金额重算 2dp 百分比以满足需求 7.1/7.3 的 2 位小数口径。</p>
     *
     * <p>门控（需求 7.3、7.4）：可用当且仅当 {@code ov.hasBudget() 且 totalBudget > 0.00 且 spent > 0.00
     * 且 spent ≤ totalBudget}（即预算状态非 OVER）；否则不可用（{@code NO_BUDGET_OR_OVER}），不抛错。
     * 未设预算时 {@code totalBudget} 可能为 {@code null}，先短路以避免 NPE。{@code month} 缺省当前自然月、
     * 格式校验均已在 {@link ShareCardQuery#of} 完成（需求 7.2、7.6）。</p>
     */
    private Evaluation evaluateBudgetAchieved(Long ledgerId, ShareCardQuery query) {
        // 复用预算总览口径（同口径、Asia/Shanghai、排除 transfer、金额 2dp HALF_UP，需求 7.1、7.2）。
        BudgetOverviewResponse ov = budgetService.overview(ledgerId, query.month());

        // 门控：未设预算 → 不可用（此时 totalBudget 可能为 null，先短路避免 NPE，需求 7.4）。
        if (!ov.hasBudget() || ov.totalBudget() == null || ov.spent() == null) {
            return Evaluation.unavailable("NO_BUDGET_OR_OVER"); // 需求 7.4
        }

        BigDecimal totalBudget = scale(ov.totalBudget());
        BigDecimal spent = scale(ov.spent());

        // 门控：总预算 > 0.00 且 已用 > 0.00 且 已用 ≤ 总预算（即预算状态非 OVER，需求 7.3、7.4）。
        boolean achieved = totalBudget.compareTo(BigDecimal.ZERO) > 0
                && spent.compareTo(BigDecimal.ZERO) > 0
                && spent.compareTo(totalBudget) <= 0;
        if (!achieved) {
            return Evaluation.unavailable("NO_BUDGET_OR_OVER"); // 需求 7.4
        }

        // 剩余取预算总览口径（= 总预算 − 已用），保 2dp（需求 7.3）。
        BigDecimal remaining = ov.remaining() != null
                ? scale(ov.remaining())
                : scale(totalBudget.subtract(spent));

        // 已用百分比 = 已用支出 ÷ 本月预算 × 100（2dp HALF_UP，与同一 spent/totalBudget 同源，需求 7.1、7.3）。
        BigDecimal usedPercent = spent
                .multiply(BigDecimal.valueOf(100))
                .divide(totalBudget, 2, RoundingMode.HALF_UP);

        ShareCardCore core = new ShareCardCore(
                null, null, null,
                ov.month(), null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                totalBudget, spent, remaining, usedPercent, ov.status(),
                null, null, null, null, null, null);
        return Evaluation.available(core);
    }

    /**
     * LEVEL_UP（成长升级，账本无关；需求 8）评估器——<b>只读、不触发结算</b>。
     *
     * <p>取数（需求 1.10、8.1、8.6）：以 {@code userGrowthRepository.findById}（<b>非</b>
     * {@code findForUpdateById}，不加写锁、不结算）读取已持久化成长档案；等级换算与 {@link GrowthQueryService}
     * 概览路径<b>同一实现同值</b>——直接读取档案物化的 {@code level}/{@code exp}（无档案降级为 Lv1 / 0 经验，
     * 与概览一致），{@code currentLevelExp = growthLevelCurve.threshold(level)}、
     * {@code expInCurrentLevel = exp − currentLevelExp}、{@code maxLevelReached = level >= }
     * {@link GrowthLevelCurve#MAX_LEVEL}（100）。因此本卡片的等级/经验与 {@code /api/growth} 在同一时刻的
     * 同名两项取值相等（需求 8.6）。</p>
     *
     * <p>门控（需求 8.2、8.3）：可用当且仅当 {@code level >= 2}；核心数据携带 {@code level}、{@code exp}、
     * {@code expInCurrentLevel}。{@code level == 1}（尚未产生任何升级）不可用（{@code LEVEL_TOO_LOW}），
     * 不抛错。满级语义（需求 8.4）：{@code level == 100} 时 {@code maxLevelReached=true} 且以满级标识替代
     * 升级进度，{@code nextLevelExp}/{@code expToNextLevel} 置 {@code null}；未满级时
     * {@code nextLevelExp = threshold(level + 1)}、{@code expToNextLevel = nextLevelExp − exp}。</p>
     */
    private Evaluation evaluateLevelUp(Long userId, ShareCardQuery query) {
        // 只读读取成长档案（findById 不加写锁、不结算，需求 13.1）；无档案降级为 Lv1 / 0 经验（与概览一致）。
        UserGrowth profile = userGrowthRepository.findById(userId).orElse(null);
        int level = profile != null ? profile.getLevel() : 1;
        long exp = profile != null ? profile.getExp() : 0L;

        // 门控：仅等级 ≥ 2（已产生过升级）可用；等级 1 不可用、不抛错（需求 8.2、8.3）。
        if (level < 2) {
            return Evaluation.unavailable("LEVEL_TOO_LOW"); // 需求 8.3
        }

        // 等级换算：与 GrowthQueryService 同一实现同值（需求 1.10、8.1、8.6）。
        boolean maxLevelReached = level >= GrowthLevelCurve.MAX_LEVEL;
        long currentLevelExp = growthLevelCurve.threshold(level);
        long expInCurrentLevel = exp - currentLevelExp;

        // 满级时以满级标识替代升级进度，nextLevelExp/expToNextLevel 置 null（需求 8.4）。
        Long nextLevelExp = maxLevelReached ? null : growthLevelCurve.threshold(level + 1);
        Long expToNextLevel = maxLevelReached ? null : nextLevelExp - exp;

        ShareCardCore core = new ShareCardCore(
                null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null,
                level, exp, expInCurrentLevel, maxLevelReached, nextLevelExp, expToNextLevel);
        return Evaluation.available(core);
    }

    /**
     * 单张卡片评估结果（内部只读辅助，非持久化实体）。
     *
     * <p>可用/不可用语义与 {@link ShareCardResponse} 一致：{@code available=true} ⟺ {@code core} 非空、
     * {@code unavailableReason=null}；{@code available=false} ⟺ {@code core=null}、{@code unavailableReason}
     * 为非空原因串（需求 3.4、4.5、5.5、6.3、7.4、8.3）。</p>
     *
     * @param available         卡片是否可用
     * @param unavailableReason 不可用原因（可用时为 {@code null}）
     * @param core              核心数据（不可用时为 {@code null}）
     */
    record Evaluation(boolean available, String unavailableReason, ShareCardCore core) {

        /** 可用：携带核心数据、无不可用原因。 */
        static Evaluation available(ShareCardCore core) {
            return new Evaluation(true, null, core);
        }

        /** 不可用：无核心数据、携带不可用原因。 */
        static Evaluation unavailable(String reason) {
            return new Evaluation(false, reason, null);
        }
    }
}
