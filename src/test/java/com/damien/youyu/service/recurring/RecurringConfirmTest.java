package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.TransactionService;

/**
 * {@link RecurringPendingItemService#confirm} 的示例 / 边界集成测试（tasks 5.2，
 * 需求 4.1、4.2、4.3、4.5、4.6、4.7、4.8、4.9、9.7）。
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实 {@link TransactionService}、不用测试级事务</h2>
 * <p>确认入账<b>刻意复用既有 {@link TransactionService#create}</b>（账户加锁 + 单事务原子余额更新），
 * 以证明其与普通手动记账在流水 / 余额口径上的一致（需求 4.1、4.7、9.7）。故本测试注入真实交易服务对真实
 * H2（{@code MODE=MySQL}）读写，不加测试级 {@code @Transactional}（那会在方法结束回滚，掩盖确认的真实提交
 * 与余额变更），清理改为每个用例前显式清库（{@link #reset()}），并用独立命名内存库避免污染其它切片测试。</p>
 *
 * <p>{@link TransactionService} 用 {@link MockitoSpyBean} 包裹：<b>未打桩时委托真实实现</b>（故上述 6 组
 * 断言仍走真实建交易 + 余额更新链路），仅「事务原子性」用例对 {@code create} 打桩抛错，以在确认事务中段注入
 * 失败、验证整体回滚后待确认项保持 {@code PENDING} 且余额零变动（需求 4.2）。</p>
 *
 * <p>时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使确认默认记账时间
 * （期次到期日 00:00）与 {@code updated_at} 可确定性断言。规则 / 待确认项直接经仓库落库（绕过创建校验），
 * 聚焦确认本身。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-confirm-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringConfirmTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long OTHER_USER = 2L;
    private static final long LEDGER = 100L;
    private static final long OTHER_LEDGER = 200L;
    private static final LocalDate OCCURRENCE = LocalDate.of(2025, 6, 5);

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

    /** 未打桩时委托真实交易服务（保证与手动记账口径一致）；仅原子性用例对 create 打桩抛错。 */
    @MockitoSpyBean
    private TransactionService transactionService;

    private Long accountId;
    private Long categoryId;
    private Long altCategoryId;

    @BeforeEach
    void reset() {
        // 清理不靠回滚（确认真实提交）：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountId = saveAccount(ALICE, new BigDecimal("1000.00"));
        linkAccountToLedger(accountId, LEDGER);
        categoryId = saveCategory(LEDGER, "房租");
        altCategoryId = saveCategory(LEDGER, "水电");
    }

    // ---------------- fixtures ----------------

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

    /** 直接落库一条 ACTIVE 每月规则（绕过创建校验，聚焦确认）。 */
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

    /** 常规：模板与规则一致、快照有效、状态 PENDING 的待确认项。 */
    private RecurringPendingItem seedPending(BigDecimal amount) {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId, amount, "房租");
        return savePendingItem(rule, OCCURRENCE, categoryId, accountId, amount, "房租",
                PendingStatus.PENDING);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // ---------------- 确认：建流水 + 更新余额 + 置 CONFIRMED（需求 4.1、4.7、9.7） ----------------

    @Test
    void confirm_createsTransactionUpdatesBalanceAndSetsConfirmed() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        RecurringPendingItem confirmed =
                service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null);

        // 恰一条真实流水，字段与快照一致，归属当前账本（与手动记账口径一致）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        Transaction tx = transactionRepository.findAll().get(0);
        assertThat(tx.getLedgerId()).isEqualTo(LEDGER);
        assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.getAmount()).isEqualByComparingTo("300.00");
        assertThat(tx.getAccountId()).isEqualTo(accountId);
        assertThat(tx.getCategoryId()).isEqualTo(categoryId);
        // 记账时间缺省取期次到期日 00:00（Asia/Shanghai）。
        assertThat(tx.getOccurredAt()).isEqualTo(OCCURRENCE.atStartOfDay());

        // 账户余额按支出方向恰减 amount（1000 - 300 = 700）。
        assertThat(balanceOf(accountId)).isEqualByComparingTo("700.00");

        // 待确认项置 CONFIRMED 并回填 confirmedTransactionId。
        assertThat(confirmed.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedTransactionId()).isEqualTo(tx.getId());
        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(reloaded.getConfirmedTransactionId()).isEqualTo(tx.getId());
    }

    // ---------------- 修改后确认：用覆盖值，不触碰规则与期次键（需求 4.3、4.8） ----------------

    @Test
    void modifyThenConfirm_usesOverridesAndDoesNotTouchRuleOrOccurrenceKey() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem item = savePendingItem(rule, OCCURRENCE, categoryId, accountId,
                new BigDecimal("300.00"), "房租", PendingStatus.PENDING);

        LocalDateTime customOccurredAt = LocalDate.of(2025, 6, 10).atTime(9, 30);
        RecurringPendingItem confirmed = service.confirm(ALICE, LEDGER, item.getId(),
                new BigDecimal("500.00"), altCategoryId, accountId, "改后备注", customOccurredAt);

        // 流水取覆盖值。
        Transaction tx = transactionRepository.findAll().get(0);
        assertThat(tx.getAmount()).isEqualByComparingTo("500.00");
        assertThat(tx.getCategoryId()).isEqualTo(altCategoryId);
        assertThat(tx.getNote()).isEqualTo("改后备注");
        assertThat(tx.getOccurredAt()).isEqualTo(customOccurredAt);
        // 余额按覆盖金额变动（1000 - 500 = 500）。
        assertThat(balanceOf(accountId)).isEqualByComparingTo("500.00");

        // 不改动来源规则的模板字段（需求 4.3）。
        RecurringRule reloadedRule = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(reloadedRule.getAmount()).isEqualByComparingTo("300.00");
        assertThat(reloadedRule.getCategoryId()).isEqualTo(categoryId);
        assertThat(reloadedRule.getNote()).isEqualTo("房租");

        // 不改动期次到期日与唯一键（需求 4.3）。
        assertThat(confirmed.getOccurrenceDate()).isEqualTo(OCCURRENCE);
        assertThat(confirmed.getRuleId()).isEqualTo(rule.getId());
    }

    // ---------------- 修改后金额非法：拒绝、保持 PENDING、零副作用（需求 4.8） ----------------

    @Test
    void confirm_withInvalidAmountOverride_rejectedAndStaysPending() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        // 覆盖金额小于下限 0.01 → AMOUNT_INVALID。
        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(),
                        new BigDecimal("0.00"), null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");

        assertNoSideEffects(item.getId());
    }

    @Test
    void confirm_withTooLongNoteOverride_rejectedAndStaysPending() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        String tooLong = "x".repeat(201);
        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(),
                        null, null, null, tooLong, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOTE_TOO_LONG");

        assertNoSideEffects(item.getId());
    }

    // ---------------- 已处理：再次确认 → RECURRING_ITEM_ALREADY_PROCESSED（需求 4.5、4.9） ----------------

    @Test
    void confirm_alreadyConfirmed_returnsAlreadyProcessed() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));
        service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("700.00");

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");

        // 至多一条流水、至多一次余额变动（需求 4.9）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("700.00");
    }

    @Test
    void confirm_alreadySkipped_returnsAlreadyProcessed() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem item = savePendingItem(rule, OCCURRENCE, categoryId, accountId,
                new BigDecimal("300.00"), "房租", PendingStatus.SKIPPED);

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");

        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 目标缺失：分类 / 账户不存在 → RECURRING_ITEM_TARGET_MISSING（需求 4.6） ----------------

    @Test
    void confirm_withMissingCategory_returnsTargetMissing() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        // 快照分类指向当前账本不存在的分类 id。
        RecurringPendingItem item = savePendingItem(rule, OCCURRENCE, 999_999L, accountId,
                new BigDecimal("300.00"), "房租", PendingStatus.PENDING);

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_TARGET_MISSING");
        assertThat(ex.getField()).isEqualTo("categoryId");

        assertNoSideEffects(item.getId());
    }

    @Test
    void confirm_withMissingAccount_returnsTargetMissing() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        // 快照账户指向当前账本不可用的账户 id。
        RecurringPendingItem item = savePendingItem(rule, OCCURRENCE, categoryId, 999_999L,
                new BigDecimal("300.00"), "房租", PendingStatus.PENDING);

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_TARGET_MISSING");
        assertThat(ex.getField()).isEqualTo("accountId");

        assertNoSideEffects(item.getId());
    }

    // ---------------- 跨租户：跨账本 / 跨用户 / 不存在 → NOT_FOUND（需求 8.4、8.5） ----------------

    @Test
    void confirm_crossLedger_returnsNotFound() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, OTHER_LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");

        assertNoSideEffects(item.getId());
    }

    @Test
    void confirm_crossUser_returnsNotFound() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        ApiException ex = catchThrowableOfType(
                () -> service.confirm(OTHER_USER, LEDGER, item.getId(), null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");

        assertNoSideEffects(item.getId());
    }

    @Test
    void confirm_nonexistentItem_returnsNotFound() {
        ApiException ex = catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, 999_999L, null, null, null, null, null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 事务原子性：中段失败整体回滚，保持 PENDING、零余额变动（需求 4.2） ----------------

    @Test
    void confirm_whenTransactionCreationFails_rollsBackAndStaysPending() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        // 在确认事务中段注入建交易失败（唯一打桩用例；其余用例委托真实交易服务）。
        doThrow(new RuntimeException("boom during ledger entry"))
                .when(transactionService).create(eq(ALICE), eq(LEDGER), anyString(),
                        any(BigDecimal.class), anyLong(), anyLong(), any(LocalDateTime.class), any());

        catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                RuntimeException.class);

        // 整体回滚：闸门置 CONFIRMED 被撤销，项保持 PENDING；不生成流水、不改余额（需求 4.2）。
        assertNoSideEffects(item.getId());
    }

    /** 断言零副作用：项仍 PENDING、无流水、账户余额仍为初始 1000.00。 */
    private void assertNoSideEffects(Long itemId) {
        RecurringPendingItem reloaded = pendingItemRepository.findById(itemId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reloaded.getConfirmedTransactionId()).isNull();
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }
}
