package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

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
 * {@link RecurringRuleService#create} 的<b>创建边界</b>单元测试（tasks 3.5，H2 + 真实 Repository、
 * 固定 {@link Clock}）。本类与 {@code RecurringRuleServiceTest} 共存但互补：只补齐后者未覆盖的边界端点，
 * 不重复其已有用例。
 *
 * <p>覆盖三组边界：</p>
 * <ul>
 *   <li><b>开始日期缺省（需求 1.5）：</b>补充「显式指定开始日期时原样保留」与「缺省落在月末边界日仍取创建当日」，
 *       与 {@code RecurringRuleServiceTest#create_missingStartDate_defaultsToCreationDay} 互补。</li>
 *   <li><b>COUNT 端点（需求 1.7）：</b>N=1 与 N=9999 有效端点、N=0 与 N=10000 越界端点。</li>
 *   <li><b>星期几集合边界（需求 2.10）：</b>全集 {1..7} 有效、单元素 {7} 有效、含 0 与含 8 非法。</li>
 * </ul>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringRuleCreateBoundaryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2025-02-28 23:30 (Asia/Shanghai) → 创建当日为 2025-02-28（平年 2 月最后一日，月末边界）。
    private static final Instant T0 = Instant.parse("2025-02-28T15:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 2, 28);
    private static final long ALICE = 1L;

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

    private RecurringRuleService service() {
        Clock clock = Clock.fixed(T0, ZONE);
        LedgerAccountResolver resolver =
                new LedgerAccountResolver(accountRepository, accountLedgerRepository);
        return new RecurringRuleService(ruleRepository, pendingItemRepository, categoryRepository,
                resolver, new RecurringTemplateValidator(), clock);
    }

    // ---------------- fixtures ----------------

    private Ledger ledger(long ownerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
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
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("房租");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private Account account(long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
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
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        AccountLedger al = new AccountLedger();
        al.setAccountId(accountId);
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(visibleToOthers);
        al.setShowBalance(false);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
    }

    /** Alice 个人账本 + 一个分类 + 一个 Alice 参与该账本的账户。 */
    private Fixture aliceFixture() {
        Ledger l = ledger(ALICE);
        Category cat = category(l.getId());
        Account acc = account(ALICE);
        link(acc.getId(), l.getId(), true);
        return new Fixture(l.getId(), cat.getId(), acc.getId());
    }

    private record Fixture(Long ledgerId, Long categoryId, Long accountId) { }

    private long ruleCount() {
        return ruleRepository.count();
    }

    // ==================== 开始日期缺省边界（需求 1.5） ====================

    @Test
    void create_explicitStartDate_isPreservedVerbatim() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                LocalDate.of(2025, 12, 31), EndCondition.NEVER, null, null);

        // 显式指定开始日期时原样保留，不被创建当日覆盖。
        assertThat(rule.getStartDate()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void create_missingStartDate_defaultsToCreationDayOnMonthEndBoundary() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "income",
                new BigDecimal("100.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null);

        // 未指定开始日期 → 取创建当日（Asia/Shanghai），即使当日恰为平年 2 月月末 28 日。
        assertThat(rule.getStartDate()).isEqualTo(TODAY);
    }

    // ==================== COUNT 端点边界（需求 1.7） ====================

    @Test
    void create_countN_one_isValid() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.COUNT, null, 1);

        assertThat(rule.getEndCondition()).isEqualTo(EndCondition.COUNT);
        assertThat(rule.getCountN()).isEqualTo(1);
    }

    @Test
    void create_countN_maximum9999_isValid() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.COUNT, null, 9999);

        assertThat(rule.getEndCondition()).isEqualTo(EndCondition.COUNT);
        assertThat(rule.getCountN()).isEqualTo(9999);
    }

    @Test
    void create_countN_zero_rejectedWithEndConditionInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.COUNT, null, 0), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertThat(ex.getField()).isEqualTo("endCondition");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_countN_10000_rejectedWithEndConditionInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.COUNT, null, 10000), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertThat(ruleCount()).isZero();
    }

    // ==================== 星期几集合边界（需求 2.10） ====================

    @Test
    void create_weeklyFullSet_isValidAndNormalized() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(1, 2, 3, 4, 5, 6, 7), null, false, null, null,
                null, EndCondition.NEVER, null, null);

        // 全集 {1..7} 合法，规范化为稳定升序逗号串。
        assertThat(rule.getFrequency()).isEqualTo(Frequency.WEEKLY);
        assertThat(rule.getWeeklyDays()).isEqualTo("1,2,3,4,5,6,7");
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.ACTIVE);
    }

    @Test
    void create_weeklySingletonSunday_isValid() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(7), null, false, null, null,
                null, EndCondition.NEVER, null, null);

        // 单元素 {7}（周日）合法。
        assertThat(rule.getWeeklyDays()).isEqualTo("7");
    }

    @Test
    void create_weeklyContainsZero_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(0, 3), null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        // 含下界外取值 0 → 频率非法。
        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ex.getField()).isEqualTo("frequency");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_weeklyContainsEight_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(6, 8), null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        // 含上界外取值 8 → 频率非法。
        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ruleCount()).isZero();
    }
}
