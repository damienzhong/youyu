package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.TransactionService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 6: 确认账户守恒且与手动记账口径一致
 *
 * <p>{@link RecurringPendingItemService#confirm} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 6：</p>
 *
 * <p><em>对任意</em> {@link PendingStatus#PENDING} 待确认项，确认（含修改后确认）后：<b>恰生成一条真实流水</b>、
 * 对应账户余额<b>恰变动</b> {@code +amount}（收入）或 {@code −amount}（支出）、该项状态置
 * {@link PendingStatus#CONFIRMED}；修改后确认时流水字段取用户改后的值，而<b>原规则模板字段</b>与该项的
 * {@code occurrenceDate} 及唯一约束键 {@code (rule_id, occurrence_date)} <b>保持不变</b>；对相同的有效
 * {@code (type, amount, account, category, note, occurredAt)}，经确认入账与经<b>手动</b>
 * {@link TransactionService#create} 对账户余额的影响与流水关键字段一致；全程金额以 {@link BigDecimal} 保留
 * 2 位小数（HALF_UP）。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实 {@link TransactionService}、不用测试级事务</h2>
 * <p>与 {@link RecurringConfirmTest} 同源：确认入账<b>刻意复用既有 {@link TransactionService#create}</b>
 * （账户加锁 + 单事务原子余额更新），本属性正是要证明其与普通手动记账在流水 / 余额口径上的一致
 * （需求 4.1、4.7、9.7）。故注入<b>真实</b>交易服务对真实 H2（{@code MODE=MySQL}）读写，不加测试级
 * {@code @Transactional}（那会在方法结束回滚、掩盖确认与手动记账的真实提交与余额变更），清理改为每个 try 前
 * 显式清库（{@link #resetAndInject()}），并用独立命名内存库避免污染其它切片测试。</p>
 *
 * <p><b>手动对账口径：</b>为每个 try 播种两条初始余额相同的账户——待确认项确认作用于<b>有效账户</b>
 * （{@code recAccount}，可能被 accountOverride 改写），另有一条镜像账户 {@code manAccount} 起始余额相同、
 * 经<b>手动</b> {@link TransactionService#create} 以<b>完全相同的有效值</b>记账。二者对各自账户的余额变动与
 * 流水关键字段（type / amount / categoryId / note / occurredAt）逐一相等，即证「确认入账 == 手动记账口径」。</p>
 *
 * <p>时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使确认默认记账时间
 * （期次到期日 00:00）可确定性断言。jqwik 属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension} 不生效：
 * 依赖注入由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文静态缓存复用），同一
 * {@link BeforeTry} 内随即显式清库并重新播种，使各 try 互不串味。规则 / 待确认项直接经仓库落库（绕过创建校验），
 * 聚焦确认本身。</p>
 *
 * <p><strong>Validates: Requirements 4.1, 4.3, 4.7, 9.7</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-confirm-conservation-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringConfirmConservationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    /** 初始余额取足够大值，使单笔支出后余额仍为正（金额上界 999999.99 < 初始）。 */
    private static final BigDecimal INITIAL = new BigDecimal("1000000.00");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private RecurringPendingItemService service;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    /** 两条可选中的账户（A 为规则 / 快照账户，B 供 accountOverride 选中）。 */
    private Long accountA;
    private Long accountB;
    /** 手动对账镜像账户（起始余额与 A/B 相同）。 */
    private Long manualAccount;
    /** 两个当前账本分类（cat1 为模板分类，cat2 供 categoryOverride 选中）。 */
    private Long cat1;
    private Long cat2;

    @BeforeTry
    void resetAndInject() throws Exception {
        // jqwik 不走 SpringExtension：手工触发依赖注入（上下文缓存复用）。
        new TestContextManager(RecurringConfirmConservationPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（确认 / 手动记账真实提交）：每个 try 前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountA = seedAccount(INITIAL);
        accountB = seedAccount(INITIAL);
        manualAccount = seedAccount(INITIAL);
        linkAccountToLedger(accountA, LEDGER);
        linkAccountToLedger(accountB, LEDGER);
        linkAccountToLedger(manualAccount, LEDGER);
        cat1 = seedCategory("房租");
        cat2 = seedCategory("水电");
    }

    // =====================================================================
    // Property 6
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 6: 确认账户守恒且与手动记账口径一致
     *
     * <p>确认（含修改后确认）后：恰一条流水、有效账户余额恰变动 ±amount、项置 CONFIRMED 并回填流水 id；
     * 修改后确认取覆盖值而原规则模板 / 期次到期日 / 唯一键不变；与手动 {@link TransactionService#create}
     * 对相同有效值的余额影响与流水关键字段一致；金额恒 2 位小数。</p>
     *
     * <p><strong>Validates: Requirements 4.1, 4.3, 4.7, 9.7</strong></p>
     */
    @Property(tries = 100)
    void confirmConservesBalanceAndMatchesManualEntry(@ForAll("scenarios") Scenario s) {
        // ---- 落库规则（模板字段固定为 A / cat1 / 规则金额 / 规则备注）与其待确认项（快照=模板）。----
        RecurringRule rule = saveRule(s.type(), s.ruleAmount(), cat1, accountA, s.ruleNote());
        RecurringPendingItem item = savePending(rule, s.occurrenceDate(),
                cat1, accountA, s.ruleAmount(), s.ruleNote());

        // 记录规则模板与期次键的原值，用于「修改后确认不改动来源」断言。
        BigDecimal ruleAmountBefore = rule.getAmount();
        Long ruleCategoryBefore = rule.getCategoryId();
        Long ruleAccountBefore = rule.getAccountId();
        String ruleNoteBefore = rule.getNote();
        LocalDate occurrenceBefore = item.getOccurrenceDate();
        Long ruleIdBefore = item.getRuleId();

        // ---- 把「选择器」解析为当前 try 的真实 id（避免把跨 try 递增的 DB id 烘焙进样本）。----
        Long categoryOverride = s.categorySel() == null ? null : (s.categorySel() == 0 ? cat1 : cat2);
        Long accountOverride = s.accountSel() == null ? null : (s.accountSel() == 0 ? accountA : accountB);

        // ---- 计算「有效值」（覆盖优先，否则快照），与 confirm 内部同口径。----
        BigDecimal effAmount = s.amountOverride() != null ? s.amountOverride() : s.ruleAmount();
        Long effCategory = categoryOverride != null ? categoryOverride : cat1;
        Long effAccount = accountOverride != null ? accountOverride : accountA;
        String effNote = s.noteOverride() != null ? s.noteOverride() : s.ruleNote();
        LocalDateTime effOccurredAt = s.occurredAtOverride() != null
                ? s.occurredAtOverride()
                : s.occurrenceDate().atStartOfDay();
        TransactionType expectedType = "income".equals(s.type())
                ? TransactionType.INCOME
                : TransactionType.EXPENSE;
        BigDecimal expectedDelta = expectedType == TransactionType.INCOME
                ? effAmount
                : effAmount.negate();

        // ---- 确认（可能携带覆盖值）。----
        RecurringPendingItem confirmed = service.confirm(ALICE, LEDGER, item.getId(),
                s.amountOverride(), categoryOverride, accountOverride,
                s.noteOverride(), s.occurredAtOverride());

        // ---- 断言 1：恰生成一条真实流水（需求 4.1）。----
        assertThat(transactionRepository.count())
                .as("确认应恰生成一条流水")
                .isEqualTo(1);
        Transaction recTx = transactionRepository.findAll().get(0);

        // ---- 断言 2：项置 CONFIRMED 并回填 confirmedTransactionId（需求 4.1）。----
        assertThat(confirmed.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedTransactionId()).isEqualTo(recTx.getId());
        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(reloaded.getConfirmedTransactionId()).isEqualTo(recTx.getId());

        // ---- 断言 3：有效账户余额恰变动 ±amount（需求 4.1、9.7）。----
        BigDecimal recBalanceAfter = balanceOf(effAccount);
        assertThat(recBalanceAfter)
                .as("有效账户余额应恰变动 %s", expectedDelta)
                .isEqualByComparingTo(INITIAL.add(expectedDelta));
        // 未参与的其它可选账户余额不变。
        Long untouchedSelectable = effAccount.equals(accountA) ? accountB : accountA;
        assertThat(balanceOf(untouchedSelectable))
                .as("未参与确认的账户余额不应变化")
                .isEqualByComparingTo(INITIAL);

        // ---- 断言 4：修改后确认取覆盖值，且不改动来源规则模板 / 期次到期日 / 唯一键（需求 4.3）。----
        assertThat(recTx.getType()).isEqualTo(expectedType);
        assertThat(recTx.getAmount()).isEqualByComparingTo(effAmount);
        assertThat(recTx.getCategoryId()).isEqualTo(effCategory);
        assertThat(recTx.getAccountId()).isEqualTo(effAccount);
        assertThat(recTx.getNote()).isEqualTo(effNote);
        assertThat(recTx.getOccurredAt()).isEqualTo(effOccurredAt);
        RecurringRule reloadedRule = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(reloadedRule.getAmount()).isEqualByComparingTo(ruleAmountBefore);
        assertThat(reloadedRule.getCategoryId()).isEqualTo(ruleCategoryBefore);
        assertThat(reloadedRule.getAccountId()).isEqualTo(ruleAccountBefore);
        assertThat(reloadedRule.getNote()).isEqualTo(ruleNoteBefore);
        assertThat(reloaded.getOccurrenceDate()).isEqualTo(occurrenceBefore);
        assertThat(reloaded.getRuleId()).isEqualTo(ruleIdBefore);

        // ---- 断言 5：金额以 2 位小数存储（需求 9.7）。----
        assertThat(recTx.getAmount().scale())
                .as("流水金额应保留 2 位小数")
                .isEqualTo(2);

        // ---- 断言 6：与手动 TransactionService.create 对相同有效值口径一致（需求 4.7、9.7）。----
        Transaction manTx = transactionService.create(ALICE, LEDGER, s.type(), effAmount,
                manualAccount, effCategory, effOccurredAt, effNote);
        // 手动记账后恰新增一条流水（共 2 条）。
        assertThat(transactionRepository.count()).isEqualTo(2);
        // 镜像账户余额变动与确认入账一致（起始余额相同 → 结束余额相同）。
        assertThat(balanceOf(manualAccount))
                .as("手动记账对镜像账户的余额变动应与确认入账一致")
                .isEqualByComparingTo(recBalanceAfter);
        // 流水关键字段逐一一致（账户 id 除外——两者刻意作用于不同账户以对账）。
        assertThat(manTx.getType()).isEqualTo(recTx.getType());
        assertThat(manTx.getAmount()).isEqualByComparingTo(recTx.getAmount());
        assertThat(manTx.getCategoryId()).isEqualTo(recTx.getCategoryId());
        assertThat(manTx.getNote()).isEqualTo(recTx.getNote());
        assertThat(manTx.getOccurredAt()).isEqualTo(recTx.getOccurredAt());
        assertThat(manTx.getLedgerId()).isEqualTo(recTx.getLedgerId());
    }

    // =====================================================================
    // 持久化辅助
    // =====================================================================

    private Long seedAccount(BigDecimal balance) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(balance);
        account.setCurrentBalance(balance);
        account.setSortOrder(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account).getId();
    }

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        AccountLedger link = new AccountLedger();
        link.setAccountId(accountId);
        link.setLedgerId(ledgerId);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(LocalDateTime.ofInstant(NOW, ZONE));
        accountLedgerRepository.save(link);
    }

    private Long seedCategory(String name) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category category = new Category();
        category.setUserId(ALICE);
        category.setLedgerId(LEDGER);
        category.setParentId(null);
        category.setKind(CategoryKind.EXPENSE);
        category.setName(name);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category).getId();
    }

    private RecurringRule saveRule(String type, BigDecimal amount, Long categoryId, Long accountId,
            String note) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType(type);
        rule.setAmount(amount);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote(note);
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(5);
        rule.setMonthEnd(false);
        rule.setStartDate(LocalDate.of(2025, 1, 5));
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem savePending(RecurringRule rule, LocalDate occurrenceDate,
            Long categoryId, Long accountId, BigDecimal amount, String note) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        item.setType(rule.getType());
        item.setAmount(amount);
        item.setCategoryId(categoryId);
        item.setAccountId(accountId);
        item.setNote(note);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /**
     * 一个确认场景：规则 / 快照的模板字段（type / ruleAmount / ruleNote / occurrenceDate）
     * + 各字段可空的覆盖值（modify-then-confirm）。覆盖值为 {@code null} 表示沿用快照。
     */
    record Scenario(String type, BigDecimal ruleAmount, String ruleNote, LocalDate occurrenceDate,
            BigDecimal amountOverride, Integer categorySel, Integer accountSel,
            String noteOverride, LocalDateTime occurredAtOverride) {
    }

    /** 模板 / 快照基础字段（type / ruleAmount / ruleNote / occurrenceDate）。 */
    private record BaseFields(String type, BigDecimal ruleAmount, String ruleNote,
            LocalDate occurrenceDate) {
    }

    /** 各字段可空的覆盖值（modify-then-confirm）；分类 / 账户以选择器索引表达。 */
    private record Overrides(BigDecimal amount, Integer categorySel, Integer accountSel, String note,
            LocalDateTime occurredAt) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<String> types = Arbitraries.of("expense", "income");
        // 期次到期日落于 today 前后小范围（其自身不影响余额，仅作缺省记账时间）。
        Arbitrary<LocalDate> occurrenceDates = Arbitraries.longs()
                .between(LocalDate.of(2025, 5, 1).toEpochDay(), LocalDate.of(2025, 6, 15).toEpochDay())
                .map(LocalDate::ofEpochDay);
        // 金额恰 2 位小数、[0.01, 999999.99]，以「分」构造保证精确可比且不越界。
        Arbitrary<BaseFields> baseFields =
                Combinators.combine(types, amounts(), notes(), occurrenceDates).as(BaseFields::new);

        Arbitrary<Overrides> overrides = Combinators.combine(
                        nullable(amounts()), nullable(choiceIndex()), nullable(choiceIndex()),
                        nullable(notes()), nullable(occurredAtChoice()))
                .as(Overrides::new);

        return Combinators.combine(baseFields, overrides).as((base, ov) -> new Scenario(
                base.type(), base.ruleAmount(), base.ruleNote(), base.occurrenceDate(),
                ov.amount(), ov.categorySel(), ov.accountSel(), ov.note(), ov.occurredAt()));
    }

    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(1L, 99_999_999L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    private Arbitrary<String> notes() {
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(30));
    }

    /**
     * 分类 / 账户覆盖以「选择器索引」（0 / 1）表达，而非具体 DB id：真实 id 在每个 try 由 {@link #resetAndInject()}
     * 重新播种且随 H2 自增持续递增，若把具体 id 烘焙进样本会在后续 try 指向不存在的目标而误触 TARGET_MISSING。
     * 故样本只携带索引，测试体内再解析为当前 try 的真实 id。
     */
    private Arbitrary<Integer> choiceIndex() {
        return Arbitraries.of(0, 1);
    }

    private Arbitrary<LocalDateTime> occurredAtChoice() {
        return Arbitraries.longs()
                .between(LocalDate.of(2025, 5, 1).toEpochDay(), LocalDate.of(2025, 6, 15).toEpochDay())
                .map(epoch -> LocalDate.ofEpochDay(epoch).atTime(9, 30));
    }

    /** 以约 1/4 概率产出 null（沿用快照），其余产出给定生成器的值。 */
    private <T> Arbitrary<T> nullable(Arbitrary<T> inner) {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(1, Arbitraries.just((T) null)),
                net.jqwik.api.Tuple.of(3, inner));
    }
}
