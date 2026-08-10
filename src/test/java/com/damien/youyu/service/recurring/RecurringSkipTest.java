package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

/**
 * {@link RecurringPendingItemService#skip} 的示例 / 边界集成测试（tasks 5.3，需求 4.4、4.5）。
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实 H2、不用测试级事务</h2>
 * <p>跳过是纯粹的状态迁移（{@code PENDING → SKIPPED}），需求 4.4 明确要求<b>不生成流水、不改余额</b>。
 * 本测试沿用 {@link RecurringConfirmTest} 的基座：真实 H2（{@code MODE=MySQL}）、每用例前显式清库
 * （{@link #reset()}），并用独立命名内存库避免污染其它切片测试；不加测试级 {@code @Transactional}
 * （那会在方法结束回滚，掩盖跳过的真实提交）。断言跳过后待确认项落库为 {@code SKIPPED}、交易表为空、
 * 账户余额零变动。</p>
 *
 * <p>时钟用 {@code @Primary} 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15）。规则 / 待确认项
 * 直接经仓库落库（绕过创建校验），聚焦跳过本身。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-skip-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringSkipTest {

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

    private Long accountId;
    private Long categoryId;

    @BeforeEach
    void reset() {
        // 清理不靠回滚（跳过真实提交）：每个用例前硬清相关表。
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

    /** 直接落库一条 ACTIVE 每月规则（绕过创建校验，聚焦跳过）。 */
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

    /** 常规：状态 PENDING 的待确认项。 */
    private RecurringPendingItem seedPending(PendingStatus status) {
        RecurringRule rule = saveRule(ALICE, LEDGER, categoryId, accountId,
                new BigDecimal("300.00"), "房租");
        return savePendingItem(rule, OCCURRENCE, categoryId, accountId,
                new BigDecimal("300.00"), "房租", status);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // ---------------- 跳过：置 SKIPPED，不生成流水、不改余额（需求 4.4） ----------------

    @Test
    void skip_setsSkippedWithoutTransactionOrBalanceChange() {
        RecurringPendingItem item = seedPending(PendingStatus.PENDING);

        RecurringPendingItem skipped = service.skip(ALICE, LEDGER, item.getId());

        // 状态置 SKIPPED，不回填确认流水引用。
        assertThat(skipped.getStatus()).isEqualTo(PendingStatus.SKIPPED);
        assertThat(skipped.getConfirmedTransactionId()).isNull();
        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.SKIPPED);

        // 不生成任何流水、不改变账户余额（需求 4.4）。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 已处理：再次跳过 → RECURRING_ITEM_ALREADY_PROCESSED（需求 4.5） ----------------

    @Test
    void skip_alreadySkipped_returnsAlreadyProcessed() {
        RecurringPendingItem item = seedPending(PendingStatus.SKIPPED);

        ApiException ex = catchThrowableOfType(
                () -> service.skip(ALICE, LEDGER, item.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");

        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void skip_alreadyConfirmed_returnsAlreadyProcessed() {
        RecurringPendingItem item = seedPending(PendingStatus.CONFIRMED);

        ApiException ex = catchThrowableOfType(
                () -> service.skip(ALICE, LEDGER, item.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("RECURRING_ITEM_ALREADY_PROCESSED");

        // 已确认项状态不被跳过改动。
        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 跨租户：跨账本 / 跨用户 / 不存在 → NOT_FOUND（需求 8.4、8.5） ----------------

    @Test
    void skip_crossLedger_returnsNotFound() {
        RecurringPendingItem item = seedPending(PendingStatus.PENDING);

        ApiException ex = catchThrowableOfType(
                () -> service.skip(ALICE, OTHER_LEDGER, item.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");

        // 项保持 PENDING，零副作用。
        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void skip_crossUser_returnsNotFound() {
        RecurringPendingItem item = seedPending(PendingStatus.PENDING);

        ApiException ex = catchThrowableOfType(
                () -> service.skip(OTHER_USER, LEDGER, item.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");

        RecurringPendingItem reloaded = pendingItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void skip_nonexistentItem_returnsNotFound() {
        ApiException ex = catchThrowableOfType(
                () -> service.skip(ALICE, LEDGER, 999_999L),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }
}
