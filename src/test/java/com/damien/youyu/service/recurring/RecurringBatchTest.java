package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.TransactionService;

/**
 * {@link RecurringPendingItemService#batchConfirm} / {@link RecurringPendingItemService#batchSkip} 的示例 /
 * 边界集成测试（tasks 5.4，需求 5.4、5.5、5.6）。
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实链路、不用测试级事务</h2>
 * <p>批量确认 / 跳过<b>逐条经本 bean 的 Spring 代理调用</b>单条 {@code confirm} / {@code skip}，每条以
 * {@code REQUIRED} 各自开启一个独立事务并在返回前提交 / 回滚（{@link RecurringPendingItemService} 的
 * {@code self} 自引用说明）。本测试沿用 {@link RecurringConfirmTest} / {@link RecurringSkipTest} 的基座：
 * 真实 {@link TransactionService} 对真实 H2（{@code MODE=MySQL}）读写、每用例前显式清库
 * （{@link #reset()}）、独立命名内存库避免污染其它切片、<b>不加测试级 {@code @Transactional}</b>——那会在
 * 方法结束回滚，掩盖批量逐条<b>各自提交</b>的真实性并使「逐条独立事务隔离」无法被观测。</p>
 *
 * <p>{@link TransactionService} 用 {@link MockitoSpyBean} 包裹：<b>未打桩时委托真实实现</b>（成功条目走真实
 * 建交易 + 余额更新链路），仅
 * {@link #batchConfirm_perItemTransactionIsolation_earlierCommitSurvivesLaterRollback} 一例对某笔
 * {@code create} 打桩抛错，以证明「某条失败仅回滚该条事务，先前已提交条目原样保留」的<b>逐条事务隔离</b>
 * （需求 5.4）——这正是批量方法不开外层大事务、逐条经代理独立事务的关键正确性。</p>
 *
 * <p>时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15）。规则 / 待确认项直接
 * 经仓库落库（绕过创建校验），聚焦批量本身。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-batch-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringBatchTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long OTHER_USER = 2L;
    private static final long LEDGER = 100L;
    private static final long OTHER_LEDGER = 200L;

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

    /** 未打桩时委托真实交易服务；仅隔离用例对某笔 create 打桩抛错。 */
    @MockitoSpyBean
    private TransactionService transactionService;

    private Long accountId;
    private Long categoryId;

    @BeforeEach
    void reset() {
        // 清理不靠回滚（批量逐条真实提交）：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountId = saveAccount(ALICE, new BigDecimal("1000.00"));
        linkAccountToLedger(accountId, LEDGER);
        categoryId = saveCategory(LEDGER, "房租");
    }

    // ---------------- 批量确认：混合有效 / 已处理 / 跨租户 ----------------

    /**
     * 批量确认混合条目（需求 5.4、5.6）：一条有效 {@code PENDING} + 一条已 {@code CONFIRMED} + 一条跨账本。
     * 断言有效条目入账提交（生成流水、余额变动），已处理条目记
     * {@code RECURRING_ITEM_ALREADY_PROCESSED} 失败、跨账本条目记 {@code NOT_FOUND} 失败，二者均不影响有效
     * 条目，逐条结果与成功 / 失败计数可判定。
     */
    @Test
    void batchConfirm_mixedValidAlreadyProcessedAndCrossTenant() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem valid = savePendingItem(rule, LocalDate.of(2025, 6, 5), categoryId,
                accountId, new BigDecimal("300.00"), "房租", PendingStatus.PENDING);
        RecurringPendingItem alreadyConfirmed = savePendingItem(rule, LocalDate.of(2025, 5, 5),
                categoryId, accountId, new BigDecimal("300.00"), "房租", PendingStatus.CONFIRMED);

        // 跨账本：项与规则均归属 OTHER_LEDGER，用当前 LEDGER 确认应判为 NOT_FOUND。
        RecurringRule otherRule = saveRule(ALICE, OTHER_LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem crossLedger = savePendingItem(otherRule, LocalDate.of(2025, 6, 5),
                categoryId, accountId, new BigDecimal("300.00"), "房租", PendingStatus.PENDING);

        RecurringBatchResult result = service.batchConfirm(ALICE, LEDGER,
                List.of(valid.getId(), alreadyConfirmed.getId(), crossLedger.getId()));

        // 逐条结果与计数（需求 5.6）。
        assertThat(result.succeededIds()).containsExactly(valid.getId());
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(2);
        assertThat(result.failed()).containsExactlyInAnyOrder(
                new RecurringBatchResult.Failure(alreadyConfirmed.getId(),
                        "RECURRING_ITEM_ALREADY_PROCESSED"),
                new RecurringBatchResult.Failure(crossLedger.getId(), "NOT_FOUND"));

        // 有效条目入账提交：恰一条流水、余额按支出恰减 300（1000 → 700）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("700.00");
        assertThat(reload(valid).getStatus()).isEqualTo(PendingStatus.CONFIRMED);

        // 失败条目不受影响：已确认项仍 CONFIRMED、跨账本项仍 PENDING。
        assertThat(reload(alreadyConfirmed).getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(reload(crossLedger).getStatus()).isEqualTo(PendingStatus.PENDING);
    }

    /**
     * 逐条事务隔离（需求 5.4）：批量含两条有效项，第二条建交易时注入失败。断言第一条已提交（流水 + 余额
     * 变动）<b>不被第二条的回滚牵连</b>，第二条自身回滚保持 {@code PENDING}、无流水、无额外余额变动——证明
     * 批量不开外层大事务、逐条经代理各自独立事务。
     */
    @Test
    void batchConfirm_perItemTransactionIsolation_earlierCommitSurvivesLaterRollback() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem first = savePendingItem(rule, LocalDate.of(2025, 6, 5), categoryId,
                accountId, new BigDecimal("300.00"), "房租", PendingStatus.PENDING);
        RecurringPendingItem second = savePendingItem(rule, LocalDate.of(2025, 5, 5), categoryId,
                accountId, new BigDecimal("500.00"), "房租", PendingStatus.PENDING);

        // 仅第二条（金额 500）建交易时抛错；第一条（金额 300）委托真实实现。
        doThrow(new RuntimeException("boom during ledger entry for second item"))
                .when(transactionService).create(eq(ALICE), eq(LEDGER), anyString(),
                        argThat(a -> a != null && a.compareTo(new BigDecimal("500.00")) == 0),
                        anyLong(), anyLong(), any(LocalDateTime.class), any());

        RecurringBatchResult result = service.batchConfirm(ALICE, LEDGER,
                List.of(first.getId(), second.getId()));

        // 第一条成功、第二条以回退错误码记为失败（其事务已回滚）。
        assertThat(result.succeededIds()).containsExactly(first.getId());
        assertThat(result.failed()).containsExactly(
                new RecurringBatchResult.Failure(second.getId(),
                        RecurringBatchResult.INTERNAL_ERROR_CODE));

        // 第一条提交存活：恰一条流水、余额仅反映第一条（1000 → 700）——未被第二条回滚牵连（需求 5.4）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("700.00");
        assertThat(reload(first).getStatus()).isEqualTo(PendingStatus.CONFIRMED);

        // 第二条自身回滚：保持 PENDING、无回填流水引用。
        RecurringPendingItem reloadedSecond = reload(second);
        assertThat(reloadedSecond.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reloadedSecond.getConfirmedTransactionId()).isNull();
    }

    /** 空 / null 入参：返回空结果、无副作用（需求 5.6 边界）。 */
    @Test
    void batchConfirm_emptyOrNull_returnsEmptyResult() {
        RecurringBatchResult empty = service.batchConfirm(ALICE, LEDGER, List.of());
        assertThat(empty.successCount()).isZero();
        assertThat(empty.failureCount()).isZero();

        RecurringBatchResult nullResult = service.batchConfirm(ALICE, LEDGER, null);
        assertThat(nullResult.succeededIds()).isEmpty();
        assertThat(nullResult.failed()).isEmpty();

        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 批量跳过：混合有效 / 已处理 / 跨租户 ----------------

    /**
     * 批量跳过混合条目（需求 5.5、5.6）：一条有效 {@code PENDING} + 一条已 {@code SKIPPED} + 一条已
     * {@code CONFIRMED} + 一条跨用户。断言仅有效 {@code PENDING} 置 {@code SKIPPED}，已处理条目记
     * {@code RECURRING_ITEM_ALREADY_PROCESSED} 失败、跨用户条目记 {@code NOT_FOUND} 失败且均不影响其余；
     * 全程不生成流水、不改变账户余额。
     */
    @Test
    void batchSkip_mixedValidAlreadyProcessedAndCrossTenant() {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem valid = savePendingItem(rule, LocalDate.of(2025, 6, 5), categoryId,
                accountId, new BigDecimal("300.00"), "房租", PendingStatus.PENDING);
        RecurringPendingItem alreadySkipped = savePendingItem(rule, LocalDate.of(2025, 5, 5),
                categoryId, accountId, new BigDecimal("300.00"), "房租", PendingStatus.SKIPPED);
        RecurringPendingItem alreadyConfirmed = savePendingItem(rule, LocalDate.of(2025, 4, 5),
                categoryId, accountId, new BigDecimal("300.00"), "房租", PendingStatus.CONFIRMED);

        // 跨用户：项与规则归属 OTHER_USER，用 ALICE 跳过应判为 NOT_FOUND。
        RecurringRule otherUserRule = saveRule(OTHER_USER, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        RecurringPendingItem crossUser = savePendingItem(otherUserRule, LocalDate.of(2025, 6, 5),
                categoryId, accountId, new BigDecimal("300.00"), "房租", PendingStatus.PENDING);

        RecurringBatchResult result = service.batchSkip(ALICE, LEDGER, List.of(
                valid.getId(), alreadySkipped.getId(), alreadyConfirmed.getId(), crossUser.getId()));

        // 逐条结果与计数（需求 5.6）。
        assertThat(result.succeededIds()).containsExactly(valid.getId());
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(3);
        assertThat(result.failed()).containsExactlyInAnyOrder(
                new RecurringBatchResult.Failure(alreadySkipped.getId(),
                        "RECURRING_ITEM_ALREADY_PROCESSED"),
                new RecurringBatchResult.Failure(alreadyConfirmed.getId(),
                        "RECURRING_ITEM_ALREADY_PROCESSED"),
                new RecurringBatchResult.Failure(crossUser.getId(), "NOT_FOUND"));

        // 有效项置 SKIPPED；失败条目状态不受影响。
        assertThat(reload(valid).getStatus()).isEqualTo(PendingStatus.SKIPPED);
        assertThat(reload(alreadySkipped).getStatus()).isEqualTo(PendingStatus.SKIPPED);
        assertThat(reload(alreadyConfirmed).getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(reload(crossUser).getStatus()).isEqualTo(PendingStatus.PENDING);

        // 跳过全程不生成流水、不改余额（需求 5.5）。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
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

    /** 直接落库一条 ACTIVE 每月规则（绕过创建校验，聚焦批量）。 */
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

    private RecurringPendingItem reload(RecurringPendingItem item) {
        return pendingItemRepository.findById(item.getId()).orElseThrow();
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }
}
