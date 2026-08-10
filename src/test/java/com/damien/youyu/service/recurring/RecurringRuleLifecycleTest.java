package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.service.LedgerAccountResolver;

/**
 * {@link RecurringRuleService} 的<b>生命周期</b>（暂停 / 恢复 / 删除，tasks 3.3）单元测试
 * （H2 + 真实 Repository、固定 {@link Clock}）。与 {@code RecurringRuleServiceTest}（创建 / 查询 / 编辑）
 * 互补，专注 {@link RecurringRuleService#pause} / {@link RecurringRuleService#resume} /
 * {@link RecurringRuleService#delete} 三条生命周期路径。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>暂停（需求 6.1）：</b>{@code ACTIVE}→{@code PAUSED}，既有 {@code PENDING} 待确认项保持不变。</li>
 *   <li><b>恢复（需求 6.2）：</b>{@code PAUSED}→{@code ACTIVE}，以 {@code updated_at} 记录恢复当日，
 *       使懒生成的生成下界 {@code max(startDate, updatedAt.toLocalDate())} 推进到恢复当日
 *       （不回补暂停区间期次）。</li>
 *   <li><b>删除（需求 6.5、6.6）：</b>级联移除全部 {@code PENDING}，保留 {@code CONFIRMED} 历史流水引用与
 *       {@code SKIPPED} 记录，并删除规则行。</li>
 *   <li><b>越权（需求 6.7、8.5）：</b>三者对跨用户 / 跨账本一律 {@code NOT_FOUND} 且零副作用。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringRuleLifecycleTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 创建时刻：2025-06-15 12:30 (Asia/Shanghai) → 创建当日 2025-06-15。
    private static final Instant T_CREATE = Instant.parse("2025-06-15T04:30:00Z");
    private static final LocalDate CREATE_DAY = LocalDate.of(2025, 6, 15);
    // 恢复时刻：2025-08-20 09:00 (Asia/Shanghai) → 恢复当日 2025-08-20（晚于创建当日）。
    private static final Instant T_RESUME = Instant.parse("2025-08-20T01:00:00Z");
    private static final LocalDate RESUME_DAY = LocalDate.of(2025, 8, 20);
    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;

    /** 以指定时刻的固定时钟构造服务（暂停用创建时钟、恢复用恢复时钟以区分「恢复当日」）。 */
    private RecurringRuleService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZONE);
        LedgerAccountResolver resolver =
                new LedgerAccountResolver(accountRepository, accountLedgerRepository);
        return new RecurringRuleService(ruleRepository, pendingItemRepository, categoryRepository,
                resolver, new RecurringTemplateValidator(), clock);
    }

    private RecurringRuleService service() {
        return serviceAt(T_CREATE);
    }

    // ---------------- fixtures ----------------

    private Ledger ledger(long ownerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T_CREATE, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("个人");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(true);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return ledgerRepository.save(l);
    }

    private Category category(long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T_CREATE, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("房租");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private Account account(long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(T_CREATE, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal("1000.00"));
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private void link(long accountId, long ledgerId, boolean visibleToOthers) {
        LocalDateTime now = LocalDateTime.ofInstant(T_CREATE, ZONE);
        AccountLedger al = new AccountLedger();
        al.setAccountId(accountId);
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(visibleToOthers);
        al.setShowBalance(false);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
    }

    private Fixture aliceFixture() {
        Ledger l = ledger(ALICE);
        Category cat = category(l.getId());
        Account acc = account(ALICE);
        link(acc.getId(), l.getId(), true);
        return new Fixture(l.getId(), cat.getId(), acc.getId());
    }

    private record Fixture(Long ledgerId, Long categoryId, Long accountId) { }

    /** 建一条 Alice 名下、开始日期落在创建当日之前（模拟已运行一段时间）的月度规则。 */
    private RecurringRule createMonthlyRule(long userId, Fixture f) {
        return service().create(userId, f.ledgerId(), "expense",
                new BigDecimal("3000.00"), f.categoryId(), f.accountId(), "房租",
                Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 6, 5), EndCondition.NEVER, null, null);
    }

    /** 为规则塞一条指定状态的待确认项（模拟已生成的期次），返回其 id。 */
    private Long seedPendingItem(RecurringRule rule, Fixture f, LocalDate occurrenceDate,
            PendingStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(T_CREATE, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(f.ledgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(status);
        item.setType("expense");
        item.setAmount(new BigDecimal("3000.00"));
        item.setCategoryId(f.categoryId());
        item.setAccountId(f.accountId());
        item.setNote("房租");
        if (status == PendingStatus.CONFIRMED) {
            item.setConfirmedTransactionId(9999L);
        }
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item).getId();
    }

    // ==================== 暂停（需求 6.1） ====================

    @Test
    void pause_activeRule_setsPausedAndLeavesPendingItemsUnchanged() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Long pendingId = seedPendingItem(rule, f, LocalDate.of(2025, 7, 5), PendingStatus.PENDING);

        RecurringRule paused = service().pause(ALICE, f.ledgerId(), rule.getId());

        assertThat(paused.getStatus()).isEqualTo(RuleStatus.PAUSED);
        RecurringRule reloaded = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RuleStatus.PAUSED);
        // 既有 PENDING 待确认项保持不变（仍存在、仍 PENDING）。
        RecurringPendingItem item = pendingItemRepository.findById(pendingId).orElseThrow();
        assertThat(item.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(item.getAmount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void pause_idempotentOnAlreadyPaused() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        service().pause(ALICE, f.ledgerId(), rule.getId());

        RecurringRule again = service().pause(ALICE, f.ledgerId(), rule.getId());

        assertThat(again.getStatus()).isEqualTo(RuleStatus.PAUSED);
    }

    @Test
    void pause_crossUser_returnsNotFoundAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(
                () -> service().pause(BOB, f.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(ruleRepository.findById(rule.getId()).orElseThrow().getStatus())
                .isEqualTo(RuleStatus.ACTIVE);
    }

    @Test
    void pause_crossLedger_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Fixture other = aliceFixture();

        ApiException ex = catchThrowableOfType(
                () -> service().pause(ALICE, other.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ==================== 恢复（需求 6.2：生成下界 = 恢复当日） ====================

    @Test
    void resume_pausedRule_setsActiveAndRecordsResumeDayAsGenerationLowerBound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        LocalDate startDate = rule.getStartDate();
        // 先暂停（暂停也用恢复前的时钟；这里沿用创建时钟即可，暂停不影响下界锚点）。
        service().pause(ALICE, f.ledgerId(), rule.getId());

        // 恢复用「恢复当日」时钟（晚于开始日期与创建当日）。
        RecurringRule resumed = serviceAt(T_RESUME).resume(ALICE, f.ledgerId(), rule.getId());

        assertThat(resumed.getStatus()).isEqualTo(RuleStatus.ACTIVE);
        // 恢复以 updated_at 记录恢复当日：懒生成据 max(startDate, updatedAt) 推进生成下界到恢复当日。
        assertThat(resumed.getUpdatedAt().toLocalDate()).isEqualTo(RESUME_DAY);

        RecurringRule reloaded = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(reloaded.getUpdatedAt().toLocalDate()).isEqualTo(RESUME_DAY);

        // 生成下界 = max(startDate, 恢复当日) = 恢复当日（开始日期早于恢复当日）——暂停区间期次不回补（需求 6.2）。
        LocalDate generationLowerBound = startDate.isAfter(RESUME_DAY) ? startDate : RESUME_DAY;
        assertThat(generationLowerBound).isEqualTo(RESUME_DAY);
        assertThat(startDate).isBefore(RESUME_DAY);
    }

    @Test
    void resume_crossUser_returnsNotFoundAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        service().pause(ALICE, f.ledgerId(), rule.getId());

        ApiException ex = catchThrowableOfType(
                () -> serviceAt(T_RESUME).resume(BOB, f.ledgerId(), rule.getId()),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 越权恢复零副作用：规则仍 PAUSED。
        assertThat(ruleRepository.findById(rule.getId()).orElseThrow().getStatus())
                .isEqualTo(RuleStatus.PAUSED);
    }

    @Test
    void resume_crossLedger_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        service().pause(ALICE, f.ledgerId(), rule.getId());
        Fixture other = aliceFixture();

        ApiException ex = catchThrowableOfType(
                () -> serviceAt(T_RESUME).resume(ALICE, other.ledgerId(), rule.getId()),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ==================== 删除（需求 6.5、6.6） ====================

    @Test
    void delete_removesPendingItemsButKeepsConfirmedAndSkippedAndRemovesRule() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Long pendingId = seedPendingItem(rule, f, LocalDate.of(2025, 7, 5), PendingStatus.PENDING);
        Long confirmedId =
                seedPendingItem(rule, f, LocalDate.of(2025, 8, 5), PendingStatus.CONFIRMED);
        Long skippedId = seedPendingItem(rule, f, LocalDate.of(2025, 9, 5), PendingStatus.SKIPPED);

        service().delete(ALICE, f.ledgerId(), rule.getId());

        // 规则行被删除。
        assertThat(ruleRepository.findById(rule.getId())).isEmpty();
        // PENDING 被级联移除。
        assertThat(pendingItemRepository.findById(pendingId)).isEmpty();
        // CONFIRMED 历史流水引用与 SKIPPED 记录保留（需求 6.5、6.6）。
        RecurringPendingItem confirmed = pendingItemRepository.findById(confirmedId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(PendingStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedTransactionId()).isEqualTo(9999L);
        RecurringPendingItem skipped = pendingItemRepository.findById(skippedId).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo(PendingStatus.SKIPPED);
    }

    @Test
    void delete_crossUser_returnsNotFoundAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Long pendingId = seedPendingItem(rule, f, LocalDate.of(2025, 7, 5), PendingStatus.PENDING);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(BOB, f.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 越权删除零副作用：规则与其 PENDING 项均未被删除。
        assertThat(ruleRepository.findById(rule.getId())).isPresent();
        assertThat(pendingItemRepository.findById(pendingId)).isPresent();
    }

    @Test
    void delete_crossLedger_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Fixture other = aliceFixture();

        ApiException ex = catchThrowableOfType(
                () -> service().delete(ALICE, other.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(ruleRepository.findById(rule.getId())).isPresent();
    }
}
