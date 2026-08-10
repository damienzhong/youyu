package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

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
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.TransactionService;

/**
 * {@link RecurringPendingItemService#confirm} 的<b>确认事务原子性回滚</b>单元测试（tasks 5.10，需求 4.2）。
 *
 * <h2>为何这是 {@link RecurringConfirmTest} 的补充而非重复</h2>
 * <p>{@link RecurringConfirmTest#confirm_whenTransactionCreationFails_rollsBackAndStaysPending} 在
 * {@link TransactionService#create} <b>入口即抛错</b>（{@code doThrow}），故建交易那一步<b>什么都没做</b>——
 * 它证明了「并发闸门置 {@code CONFIRMED} 被回滚、项回到 {@code PENDING}」，但<b>并未</b>证明「一条<b>已真实
 * 落库</b>的流水与<b>已发生</b>的余额变动能随整体事务回滚」。而需求 4.2 要求「交易创建 + 账户余额更新 +
 * 待确认项状态更新<b>全部提交或全部回滚</b>」，其最强证据恰是：让 {@code create} <b>真正执行</b>（插入交易行 +
 * 扣减账户余额），随后在同一事务内注入失败，断言这两处已发生的 DB 副作用连同闸门<b>一并撤销</b>。</p>
 *
 * <p>本用例用 {@link MockitoSpyBean} 包裹 {@code TransactionService}，以 {@code doAnswer} 先
 * {@code callRealMethod()} 让真实 {@code create} 在<b>同一确认事务</b>内落库流水并更新余额，<b>之后</b>再抛出
 * 运行时异常，从而验证：整体回滚后待确认项保持 {@code PENDING}、{@code confirmedTransactionId} 为空、
 * <b>无任何流水残留</b>、账户余额<b>零变动</b>（需求 4.2）。</p>
 *
 * <p>与 {@link RecurringConfirmTest} 同款：全栈 {@code @SpringBootTest} + 真实 H2（{@code MODE=MySQL}）跑真实
 * 事务边界（纯 Mockito 单测无法证明 DB 回滚），不加测试级 {@code @Transactional}（那会掩盖真实提交 / 回滚），
 * 独立命名内存库避免污染其它切片，用 {@code @Primary} 固定时钟保证可确定性断言。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-confirm-atomicity-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringConfirmAtomicityTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final LocalDate OCCURRENCE = LocalDate.of(2025, 6, 5);
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

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

    /** 未打桩时委托真实交易服务；本用例以 doAnswer 先真实建交易再抛错，验证已落库副作用一并回滚。 */
    @MockitoSpyBean
    private TransactionService transactionService;

    private Long accountId;
    private Long categoryId;

    @BeforeEach
    void reset() {
        // 清理不靠回滚（确认真实提交 / 回滚）：每个用例前硬清相关表。
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

    // ---------------- 确认事务原子性：中段失败整体回滚，保持 PENDING、零流水、零余额变动（需求 4.2） ----------------

    /**
     * 让真实 {@code create} 在同一确认事务内<b>先落库流水并扣减余额</b>，之后再注入失败，断言：整体回滚后
     * 待确认项保持 {@code PENDING}、无流水残留、余额零变动——证明「建交易 + 更新余额 + 置 CONFIRMED」
     * 全部回滚（需求 4.2）。
     */
    @Test
    void confirm_whenStepFailsAfterLedgerEntryPersisted_rollsBackEverything() {
        RecurringPendingItem item = seedPending(new BigDecimal("300.00"));

        // 先执行真实建交易（插入交易行 + 扣减账户余额，落在同一确认事务内），随后抛错触发整体回滚。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("boom after ledger entry persisted");
        }).when(transactionService).create(eq(ALICE), eq(LEDGER), anyString(),
                any(BigDecimal.class), anyLong(), anyLong(), any(LocalDateTime.class), any());

        catchThrowableOfType(
                () -> service.confirm(ALICE, LEDGER, item.getId(), null, null, null, null, null),
                RuntimeException.class);

        // 整体回滚：已落库的流水被撤销、已扣减的余额恢复、闸门置 CONFIRMED 被撤销、项回到 PENDING（需求 4.2）。
        assertNoSideEffects(item.getId());
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

    /** 断言零副作用：项仍 PENDING、无回填流水 id、无流水残留、账户余额仍为初始 1000.00。 */
    private void assertNoSideEffects(Long itemId) {
        RecurringPendingItem reloaded = pendingItemRepository.findById(itemId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reloaded.getConfirmedTransactionId()).isNull();
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo(INITIAL_BALANCE);
    }
}
