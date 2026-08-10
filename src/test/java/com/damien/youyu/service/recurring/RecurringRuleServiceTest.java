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
 * {@link RecurringRuleService#create} 的示例与边界单元测试（H2 + 真实 Repository，固定 {@link Clock}）。
 *
 * <p>验证：合法创建归属 + 初始 ACTIVE + weekly_days 规范化 + 开始日期缺省；模板字段 / 频率配置 /
 * 结束条件各类非法均拒绝且零副作用（需求 1.1–1.8、2.10）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringRuleServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2025-06-15 12:30 (Asia/Shanghai) → 创建当日为 2025-06-15。
    private static final Instant T0 = Instant.parse("2025-06-15T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
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

    /** 让某账户参与某账本（默认对他人可见）。 */
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

    // ---------------- 合法创建 ----------------

    @Test
    void create_valid_monthly_persistsActiveOwnedRule() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("3000.00"), f.categoryId(), f.accountId(), "房租",
                Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null);

        assertThat(rule.getId()).isNotNull();
        assertThat(rule.getUserId()).isEqualTo(ALICE);
        assertThat(rule.getLedgerId()).isEqualTo(f.ledgerId());
        assertThat(rule.getType()).isEqualTo("expense");
        assertThat(rule.getAmount()).isEqualByComparingTo("3000.00");
        assertThat(rule.getStatus()).isEqualTo(RuleStatus.ACTIVE);
        assertThat(rule.getFrequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(rule.getMonthDay()).isEqualTo(5);
        assertThat(rule.isMonthEnd()).isFalse();
        assertThat(rule.getStartDate()).isEqualTo(LocalDate.of(2025, 7, 5));
    }

    @Test
    void create_missingStartDate_defaultsToCreationDay() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "income",
                new BigDecimal("100.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null);

        // 未指定开始日期 → 取创建当日（Asia/Shanghai）。
        assertThat(rule.getStartDate()).isEqualTo(TODAY);
    }

    @Test
    void create_weekly_normalizesDaysToAscendingCommaString() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(5, 1, 3), null, false, null, null,
                null, EndCondition.NEVER, null, null);

        assertThat(rule.getWeeklyDays()).isEqualTo("1,3,5");
    }

    @Test
    void create_monthEnd_persistsFlagAndClearsMonthDay() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.MONTHLY, null, null, true, null, null,
                null, EndCondition.NEVER, null, null);

        assertThat(rule.isMonthEnd()).isTrue();
        assertThat(rule.getMonthDay()).isNull();
    }

    @Test
    void create_countEndCondition_persistsCountN() {
        Fixture f = aliceFixture();

        RecurringRule rule = service().create(ALICE, f.ledgerId(), "expense",
                new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                LocalDate.of(2025, 6, 1), EndCondition.COUNT, null, 12);

        assertThat(rule.getEndCondition()).isEqualTo(EndCondition.COUNT);
        assertThat(rule.getCountN()).isEqualTo(12);
        assertThat(rule.getUntilDate()).isNull();
    }

    // ---------------- 模板字段非法：零副作用 ----------------

    @Test
    void create_invalidType_rejectedWithRecurringRuleInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "transfer", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getField()).isEqualTo("type");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_amountTooManyDecimals_rejectedWithAmountInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.123"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_amountOutOfRange_rejectedWithAmountInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("1000000000.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_noteTooLong_rejectedWithNoteTooLong() {
        Fixture f = aliceFixture();
        String longNote = "x".repeat(201);

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), longNote,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOTE_TOO_LONG");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_categoryNotInLedger_rejectedWithRecurringRuleInvalid() {
        Fixture f = aliceFixture();
        // 另一个账本的分类。
        Ledger other = ledger(BOB);
        Category otherCat = category(other.getId());

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), otherCat.getId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getField()).isEqualTo("categoryId");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_accountNotUsable_rejectedWithRecurringRuleInvalid() {
        Fixture f = aliceFixture();
        // Bob 的账户，未参与 Alice 账本 → 对 Alice 不可用。
        Account bobAcc = account(BOB);

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), bobAcc.getId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getField()).isEqualTo("accountId");
        assertThat(ruleCount()).isZero();
    }

    // ---------------- 频率配置非法 ----------------

    @Test
    void create_weeklyEmptyDays_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(), null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ex.getField()).isEqualTo("frequency");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_weeklyDayOutOfRange_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.WEEKLY, Set.of(1, 8), null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_monthlyMissingDay_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.MONTHLY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_yearlyMissingMonthDay_rejectedWithFrequencyInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.YEARLY, null, null, false, 2, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertThat(ruleCount()).isZero();
    }

    // ---------------- 结束条件非法 ----------------

    @Test
    void create_untilDateBeforeStart_rejectedWithEndConditionInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                LocalDate.of(2025, 7, 1), EndCondition.UNTIL_DATE, LocalDate.of(2025, 6, 1), null),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertThat(ex.getField()).isEqualTo("endCondition");
        assertThat(ruleCount()).isZero();
    }

    @Test
    void create_countOutOfRange_rejectedWithEndConditionInvalid() {
        Fixture f = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, f.ledgerId(),
                "expense", new BigDecimal("50.00"), f.categoryId(), f.accountId(), null,
                Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.COUNT, null, 10000), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertThat(ruleCount()).isZero();
    }

    // ==================== tasks 3.2：查询 / 编辑 ====================

    /** 创建一条 Alice 名下、指定账本的月度规则，返回其 id（列表 / 详情 / 编辑用例的公共起点）。 */
    private RecurringRule createMonthlyRule(long userId, Fixture f) {
        return service().create(userId, f.ledgerId(), "expense",
                new BigDecimal("3000.00"), f.categoryId(), f.accountId(), "房租",
                Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null);
    }

    // ---------------- 列表：仅本人 + 当前账本 ----------------

    @Test
    void list_returnsOnlyOwnCurrentLedgerRulesIncludingPaused() {
        Fixture f = aliceFixture();
        RecurringRule active = createMonthlyRule(ALICE, f);
        // 同用户同账本的第二条，手动置为 PAUSED —— 列表应包含 ACTIVE 与 PAUSED。
        RecurringRule paused = createMonthlyRule(ALICE, f);
        paused.setStatus(RuleStatus.PAUSED);
        ruleRepository.save(paused);

        // 干扰项 1：Alice 的另一个账本下的规则（跨账本，不应出现）。
        Fixture aliceOther = aliceFixture();
        createMonthlyRule(ALICE, aliceOther);
        // 干扰项 2：Bob 在 Alice 当前账本 id 上的规则（跨用户，不应出现）。
        RecurringRule bobRule = createMonthlyRule(ALICE, f);
        bobRule.setUserId(BOB);
        ruleRepository.save(bobRule);

        var result = service().list(ALICE, f.ledgerId());

        assertThat(result).extracting(RecurringRule::getId)
                .containsExactlyInAnyOrder(active.getId(), paused.getId());
        assertThat(result).extracting(RecurringRule::getStatus)
                .containsExactlyInAnyOrder(RuleStatus.ACTIVE, RuleStatus.PAUSED);
    }

    @Test
    void list_emptyWhenNoRulesInCurrentLedger() {
        Fixture f = aliceFixture();

        assertThat(service().list(ALICE, f.ledgerId())).isEmpty();
    }

    // ---------------- 详情：越权 NOT_FOUND ----------------

    @Test
    void get_returnsOwnRule() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        RecurringRule found = service().get(ALICE, f.ledgerId(), rule.getId());

        assertThat(found.getId()).isEqualTo(rule.getId());
        assertThat(found.getUserId()).isEqualTo(ALICE);
    }

    @Test
    void get_crossUser_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(
                () -> service().get(BOB, f.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void get_crossLedger_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Fixture other = aliceFixture();

        ApiException ex = catchThrowableOfType(
                () -> service().get(ALICE, other.ledgerId(), rule.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 编辑：更新字段并重跑校验 ----------------

    @Test
    void update_changesFrequencyAndTemplateFields() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        LocalDateTime originalUpdatedAt = rule.getUpdatedAt();

        RecurringRule updated = service().update(ALICE, f.ledgerId(), rule.getId(), "income",
                new BigDecimal("88.00"), f.categoryId(), f.accountId(), "改后备注",
                Frequency.WEEKLY, Set.of(5, 1, 3), null, false, null, null,
                LocalDate.of(2025, 7, 1), EndCondition.COUNT, null, 6);

        assertThat(updated.getId()).isEqualTo(rule.getId());
        assertThat(updated.getType()).isEqualTo("income");
        assertThat(updated.getAmount()).isEqualByComparingTo("88.00");
        assertThat(updated.getNote()).isEqualTo("改后备注");
        assertThat(updated.getFrequency()).isEqualTo(Frequency.WEEKLY);
        assertThat(updated.getWeeklyDays()).isEqualTo("1,3,5");
        // 频率切走 MONTHLY 后 monthDay 清空。
        assertThat(updated.getMonthDay()).isNull();
        assertThat(updated.getEndCondition()).isEqualTo(EndCondition.COUNT);
        assertThat(updated.getCountN()).isEqualTo(6);
        assertThat(updated.getUntilDate()).isNull();
        // 归属与创建时间戳不因编辑改变。
        assertThat(updated.getUserId()).isEqualTo(ALICE);
        assertThat(updated.getLedgerId()).isEqualTo(f.ledgerId());
        assertThat(updated.getCreatedAt()).isEqualTo(rule.getCreatedAt());
        assertThat(updated.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void update_missingStartDate_keepsExistingStartDate() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        LocalDate originalStart = rule.getStartDate();

        RecurringRule updated = service().update(ALICE, f.ledgerId(), rule.getId(), "expense",
                new BigDecimal("3000.00"), f.categoryId(), f.accountId(), "房租",
                Frequency.MONTHLY, null, 8, false, null, null,
                null, EndCondition.NEVER, null, null);

        // 未传开始日期 → 保留规则原开始日期（编辑不隐式改动生效起点）。
        assertThat(updated.getStartDate()).isEqualTo(originalStart);
        assertThat(updated.getMonthDay()).isEqualTo(8);
    }

    // ---------------- 编辑：越权 NOT_FOUND ----------------

    @Test
    void update_crossUser_returnsNotFoundAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(() -> service().update(BOB, f.ledgerId(),
                rule.getId(), "income", new BigDecimal("1.00"), f.categoryId(), f.accountId(),
                null, Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        // 原规则未被改动。
        RecurringRule reloaded = ruleRepository.findById(rule.getId()).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo("expense");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("3000.00");
        assertThat(reloaded.getFrequency()).isEqualTo(Frequency.MONTHLY);
    }

    @Test
    void update_crossLedger_returnsNotFound() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Fixture other = aliceFixture();

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, other.ledgerId(),
                rule.getId(), "income", new BigDecimal("1.00"), other.categoryId(),
                other.accountId(), null, Frequency.DAILY, null, null, false, null, null,
                null, EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 编辑：非法字段拒绝且零副作用（同创建错误码） ----------------

    @Test
    void update_invalidAmount_rejectedWithAmountInvalidAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, f.ledgerId(),
                rule.getId(), "expense", new BigDecimal("0.001"), f.categoryId(), f.accountId(),
                "房租", Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AMOUNT_INVALID");
        assertRuleUnchanged(rule.getId());
    }

    @Test
    void update_invalidType_rejectedWithRecurringRuleInvalidAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, f.ledgerId(),
                rule.getId(), "transfer", new BigDecimal("3000.00"), f.categoryId(),
                f.accountId(), "房租", Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getField()).isEqualTo("type");
        assertRuleUnchanged(rule.getId());
    }

    @Test
    void update_weeklyEmptyDays_rejectedWithFrequencyInvalidAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, f.ledgerId(),
                rule.getId(), "expense", new BigDecimal("3000.00"), f.categoryId(),
                f.accountId(), "房租", Frequency.WEEKLY, Set.of(), null, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_FREQUENCY_INVALID");
        assertRuleUnchanged(rule.getId());
    }

    @Test
    void update_categoryNotInLedger_rejectedWithRecurringRuleInvalidAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        Ledger otherLedger = ledger(BOB);
        Category otherCat = category(otherLedger.getId());

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, f.ledgerId(),
                rule.getId(), "expense", new BigDecimal("3000.00"), otherCat.getId(),
                f.accountId(), "房租", Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.NEVER, null, null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_RULE_INVALID");
        assertThat(ex.getField()).isEqualTo("categoryId");
        assertRuleUnchanged(rule.getId());
    }

    @Test
    void update_untilDateBeforeStart_rejectedWithEndConditionInvalidAndNoChange() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, f.ledgerId(),
                rule.getId(), "expense", new BigDecimal("3000.00"), f.categoryId(),
                f.accountId(), "房租", Frequency.MONTHLY, null, 5, false, null, null,
                LocalDate.of(2025, 7, 5), EndCondition.UNTIL_DATE, LocalDate.of(2025, 6, 1), null),
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("RECURRING_END_CONDITION_INVALID");
        assertRuleUnchanged(rule.getId());
    }

    /** 断言规则各字段仍为 {@link #createMonthlyRule} 建立时的初值（非法编辑零副作用）。 */
    private void assertRuleUnchanged(Long ruleId) {
        RecurringRule reloaded = ruleRepository.findById(ruleId).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo("expense");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("3000.00");
        assertThat(reloaded.getNote()).isEqualTo("房租");
        assertThat(reloaded.getFrequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(reloaded.getMonthDay()).isEqualTo(5);
        assertThat(reloaded.getEndCondition()).isEqualTo(EndCondition.NEVER);
        assertThat(reloaded.getUntilDate()).isNull();
        assertThat(reloaded.getCountN()).isNull();
    }

    // ---------------- 编辑不触碰既有待确认项快照（需求 6.3、6.4） ----------------

    @Test
    void update_doesNotTouchExistingPendingItems() {
        Fixture f = aliceFixture();
        RecurringRule rule = createMonthlyRule(ALICE, f);
        // 模拟一条编辑前已生成的 PENDING 待确认项（持有生成时刻的模板快照）。
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(f.ledgerId());
        item.setOccurrenceDate(LocalDate.of(2025, 7, 5));
        item.setStatus(PendingStatus.PENDING);
        item.setType("expense");
        item.setAmount(new BigDecimal("3000.00"));
        item.setCategoryId(f.categoryId());
        item.setAccountId(f.accountId());
        item.setNote("房租");
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        RecurringPendingItem saved = pendingItemRepository.save(item);

        // 编辑规则的模板字段（金额 / 类型 / 备注）与频率。
        service().update(ALICE, f.ledgerId(), rule.getId(), "income",
                new BigDecimal("88.00"), f.categoryId(), f.accountId(), "改后备注",
                Frequency.DAILY, null, null, false, null, null,
                LocalDate.of(2025, 7, 1), EndCondition.NEVER, null, null);

        // 既有 PENDING 项的快照原样保留，不随规则编辑而变。
        RecurringPendingItem reloaded = pendingItemRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingStatus.PENDING);
        assertThat(reloaded.getType()).isEqualTo("expense");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("3000.00");
        assertThat(reloaded.getNote()).isEqualTo("房租");
        assertThat(reloaded.getOccurrenceDate()).isEqualTo(LocalDate.of(2025, 7, 5));
    }
}
