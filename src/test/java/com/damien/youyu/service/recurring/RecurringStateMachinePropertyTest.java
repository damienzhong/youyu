package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 8: 确认 / 跳过状态机幂等（含并发）
 *
 * <p>{@link RecurringPendingItemService#confirm} / {@link RecurringPendingItemService#skip} 的属性测试，
 * 覆盖 design.md「Correctness Properties」Property 8：</p>
 *
 * <p><em>对任意</em>待确认项与任意次数、任意交错的确认 / 跳过操作，<b>至多生成一条流水、至多对账户余额
 * 更新一次</b>；对已处于 {@code CONFIRMED} 或 {@code SKIPPED} 的项再次确认 / 跳过一律返回
 * {@code RECURRING_ITEM_ALREADY_PROCESSED} 且<b>无任何副作用</b>；改后值不满足需求 1 校验的确认被拒且该项
 * 保持 {@code PENDING}、<b>零副作用</b>（需求 4.5、4.8、4.9）。</p>
 *
 * <h2>「任意次数 / 任意交错」的建模</h2>
 * <p>把「任意次数、任意交错」建模为对<b>同一条</b>待确认项施加的一串<b>随机操作序列</b>，每步为四类之一：</p>
 * <ul>
 *   <li>{@code VALID_CONFIRM}：默认取值确认（走既有 {@link com.damien.youyu.service.TransactionService}
 *       建交易 + 更新余额）。</li>
 *   <li>{@code SKIP}：跳过本期（纯状态迁移，不触账）。</li>
 *   <li>{@code INVALID_CONFIRM_AMOUNT}：以越界 / 超精度金额覆盖确认（需求 4.8）。</li>
 *   <li>{@code INVALID_CONFIRM_NOTE}：以超长备注覆盖确认（需求 4.8）。</li>
 * </ul>
 * <p>序列长度 1–8、允许重复，天然覆盖「重复确认 / 重复跳过 / 确认后再跳过 / 跳过后再确认 / 无效改值穿插」等
 * 交错。用一个纯函数状态机<b>逐步推演期望</b>（终态至多到达一次；到达终态后任何操作皆
 * {@code RECURRING_ITEM_ALREADY_PROCESSED}；PENDING 下无效改值确认必被校验错误拒且保持 PENDING），
 * 每步后比对实际的<b>项状态、流水条数、账户余额</b>，序列末尾再断言全局不变式：</p>
 * <ul>
 *   <li><b>至多一条流水</b>：{@code transactionRepository.count() ≤ 1}（需求 4.9）。</li>
 *   <li><b>余额至多变动一个 amount</b>：最终余额相对初始的偏移量为 {@code 0} 或恰 {@code -amount}
 *       （支出方向），绝不叠加多次（需求 4.9）。</li>
 *   <li><b>无效改值零副作用</b>：任一 {@code INVALID_CONFIRM_*} 在 PENDING 下抛校验错误后，项仍 PENDING、
 *       流水与余额不变（需求 4.8）。</li>
 *   <li><b>已处理即拒</b>：终态后任何确认 / 跳过 → {@code RECURRING_ITEM_ALREADY_PROCESSED}，无副作用
 *       （需求 4.5、4.9）。</li>
 * </ul>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与 {@link RecurringConfirmTest} / {@link RecurringGenerationIdempotencePropertyTest} 同款：确认入账刻意
 * 复用真实 {@link com.damien.youyu.service.TransactionService#create}（账户加锁 + 单事务原子余额更新），
 * {@code confirm} / {@code skip} 各自 {@code @Transactional}——只有经真实 Spring 事务代理、且<b>真实提交</b>
 * 才能验证「乐观闸门先于建交易，落败者不触账」这一至多一条流水 / 至多一次余额变动的构造性保证。故不加测试级
 * {@code @Transactional}（那会在方法结束回滚，掩盖真实提交），用独立命名内存库避免污染其它切片测试，并在每次
 * 迭代前显式清库（{@link #injectAndReset()}）使各迭代（真实提交、无回滚）互不串味。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载一次）。
 * 时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15）。</p>
 *
 * <p><strong>Validates: Requirements 4.5, 4.8, 4.9</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-statemachine-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringStateMachinePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final LocalDate OCCURRENCE = LocalDate.of(2025, 6, 5);
    /** 初始余额足够大，确保支出确认后余额仍为正，断言聚焦「变动次数」而非透支语义。 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000000.00");

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

    private Long accountId;
    private Long categoryId;

    @BeforeTry
    void injectAndReset() throws Exception {
        // jqwik 不经 SpringExtension：手工注入 bean（上下文静态缓存，多次迭代只加载一次）。
        new TestContextManager(RecurringStateMachinePropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（confirm/skip 真实提交）：每次迭代前硬清相关表，使各迭代互不串味。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountId = saveAccount(ALICE, INITIAL_BALANCE);
        linkAccountToLedger(accountId, LEDGER);
        categoryId = saveCategory(LEDGER, "房租");
    }

    // =====================================================================
    // Property 8
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 8: 确认 / 跳过状态机幂等（含并发）
     *
     * <p><strong>Validates: Requirements 4.5, 4.8, 4.9</strong></p>
     */
    @Property(tries = 100)
    void confirmSkipStateMachineIsIdempotentWithZeroSideEffectOnLoserAndInvalidOverride(
            @ForAll("scenarios") Scenario scenario) {

        // 落库一条 ACTIVE 规则 + 一条 PENDING 待确认项（绕过创建校验，聚焦确认 / 跳过状态机）。
        BigDecimal amount = scenario.amount();
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId, amount, "房租");
        RecurringPendingItem seeded = savePendingItem(rule, OCCURRENCE, categoryId, accountId,
                amount, "房租", PendingStatus.PENDING);
        Long itemId = seeded.getId();

        // 纯函数期望状态机：PENDING → (CONFIRMED | SKIPPED)，终态至多到达一次。
        PendingStatus expectedState = PendingStatus.PENDING;
        int expectedTxCount = 0;
        BigDecimal expectedBalance = INITIAL_BALANCE;

        for (Op op : scenario.ops()) {
            boolean pending = expectedState == PendingStatus.PENDING;
            switch (op) {
                case VALID_CONFIRM -> {
                    if (pending) {
                        // PENDING + 有效确认：成功入账，恰一条流水、余额恰减一个 amount，置 CONFIRMED。
                        RecurringPendingItem confirmed =
                                service.confirm(ALICE, LEDGER, itemId, null, null, null, null, null);
                        assertThat(confirmed.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
                        assertThat(confirmed.getConfirmedTransactionId()).isNotNull();
                        expectedState = PendingStatus.CONFIRMED;
                        expectedTxCount = 1;
                        expectedBalance = INITIAL_BALANCE.subtract(amount);
                    } else {
                        // 终态再次确认：RECURRING_ITEM_ALREADY_PROCESSED，零副作用（需求 4.5、4.9）。
                        assertAlreadyProcessed(
                                () -> service.confirm(ALICE, LEDGER, itemId, null, null, null, null, null));
                    }
                }
                case SKIP -> {
                    if (pending) {
                        // PENDING + 跳过：置 SKIPPED，不触账。
                        RecurringPendingItem skipped = service.skip(ALICE, LEDGER, itemId);
                        assertThat(skipped.getStatus()).isEqualTo(PendingStatus.SKIPPED);
                        assertThat(skipped.getConfirmedTransactionId()).isNull();
                        expectedState = PendingStatus.SKIPPED;
                    } else {
                        // 终态再次跳过：RECURRING_ITEM_ALREADY_PROCESSED，零副作用（需求 4.5、4.9）。
                        assertAlreadyProcessed(() -> service.skip(ALICE, LEDGER, itemId));
                    }
                }
                case INVALID_CONFIRM_AMOUNT -> {
                    ApiException ex = catchThrowableOfType(
                            () -> service.confirm(ALICE, LEDGER, itemId,
                                    INVALID_AMOUNT, null, null, null, null),
                            ApiException.class);
                    assertThat(ex).as("无效金额确认必被拒").isNotNull();
                    if (pending) {
                        // PENDING：状态校验在校验金额之前先放行，故金额校验错误 AMOUNT_INVALID，保持 PENDING（需求 4.8）。
                        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
                    } else {
                        // 终态：状态校验先命中 → 已处理（需求 4.5）。
                        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");
                    }
                }
                case INVALID_CONFIRM_NOTE -> {
                    ApiException ex = catchThrowableOfType(
                            () -> service.confirm(ALICE, LEDGER, itemId,
                                    null, null, null, TOO_LONG_NOTE, null),
                            ApiException.class);
                    assertThat(ex).as("超长备注确认必被拒").isNotNull();
                    if (pending) {
                        assertThat(ex.getCode()).isEqualTo("NOTE_TOO_LONG");
                    } else {
                        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");
                    }
                }
                default -> throw new IllegalStateException("未知操作: " + op);
            }

            // 每步后：实际项状态 / 流水条数 / 账户余额恒等于期望（逐步不变式）。
            RecurringPendingItem reloaded = pendingItemRepository.findById(itemId).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("每步后项状态应与期望状态机一致")
                    .isEqualTo(expectedState);
            assertThat(transactionRepository.count())
                    .as("每步后流水条数应与期望一致（至多一条）")
                    .isEqualTo((long) expectedTxCount);
            assertThat(balanceOf(accountId))
                    .as("每步后账户余额应与期望一致（至多变动一个 amount）")
                    .isEqualByComparingTo(expectedBalance);
        }

        // 全局不变式（需求 4.9）：至多一条流水、余额相对初始至多变动一个 amount（0 或 -amount）。
        assertThat(transactionRepository.count())
                .as("整条序列至多生成一条流水")
                .isLessThanOrEqualTo(1L);
        BigDecimal delta = balanceOf(accountId).subtract(INITIAL_BALANCE);
        assertThat(delta)
                .as("余额相对初始的偏移量恰为 0 或 -amount，绝不叠加")
                .isIn(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        amount.negate().setScale(2, RoundingMode.HALF_UP));
        // 确认过（终态 CONFIRMED）必有一条流水且余额恰减 amount；否则零流水、余额不变。
        if (expectedState == PendingStatus.CONFIRMED) {
            assertThat(transactionRepository.count()).isEqualTo(1L);
            assertThat(delta).isEqualByComparingTo(amount.negate());
        } else {
            assertThat(transactionRepository.count()).isZero();
            assertThat(delta).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /** 断言操作抛 RECURRING_ITEM_ALREADY_PROCESSED（终态再次确认 / 跳过；需求 4.5、4.9）。 */
    private void assertAlreadyProcessed(Runnable action) {
        ApiException ex = catchThrowableOfType(action::run, ApiException.class);
        assertThat(ex).as("终态再次操作必被拒").isNotNull();
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");
    }

    // =====================================================================
    // fixtures
    // =====================================================================

    private Long saveAccount(long userId, BigDecimal balance) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(userId);
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

    private Long saveCategory(long ledgerId, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category category = new Category();
        category.setUserId(ALICE);
        category.setLedgerId(ledgerId);
        category.setParentId(null);
        category.setKind(CategoryKind.EXPENSE);
        category.setName(name);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category).getId();
    }

    private RecurringRule saveRule(long userId, long ledgerId, Long categoryId, Long accountId,
            BigDecimal amount, String note) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringRule rule = new RecurringRule();
        rule.setUserId(userId);
        rule.setLedgerId(ledgerId);
        rule.setType("expense");
        rule.setAmount(amount);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote(note);
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(5);
        rule.setMonthEnd(false);
        rule.setStartDate(LocalDate.of(2025, 3, 5));
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem savePendingItem(RecurringRule rule, LocalDate occurrenceDate,
            Long categoryId, Long accountId, BigDecimal amount, String note, PendingStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(status);
        item.setType("expense");
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

    /** 越界金额（低于下限 0.01）——覆盖确认必得 AMOUNT_INVALID（需求 4.8）。 */
    private static final BigDecimal INVALID_AMOUNT = new BigDecimal("0.00");
    /** 超长备注（>200）——覆盖确认必得 NOTE_TOO_LONG（需求 4.8）。 */
    private static final String TOO_LONG_NOTE = "x".repeat(201);

    /** 施加于同一待确认项的一步操作。 */
    enum Op {
        VALID_CONFIRM,
        SKIP,
        INVALID_CONFIRM_AMOUNT,
        INVALID_CONFIRM_NOTE
    }

    record Scenario(BigDecimal amount, List<Op> ops) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        // 有效金额：0.01 .. 10000.00（2 位小数），确保支出后余额仍为正。
        Arbitrary<BigDecimal> amounts = Arbitraries.longs().between(1L, 1_000_000L)
                .map(cents -> BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP));
        // 随机操作序列（长度 1–8、允许重复）：天然覆盖任意次数 / 任意交错。
        Arbitrary<List<Op>> ops = Arbitraries.of(Op.class).list().ofMinSize(1).ofMaxSize(8);
        return Combinators.combine(amounts, ops).as(Scenario::new);
    }
}
