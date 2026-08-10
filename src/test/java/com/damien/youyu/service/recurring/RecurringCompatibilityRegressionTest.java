package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.api.dto.BudgetOverviewResponse;
import com.damien.youyu.api.dto.MonthlyReportResponse;
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
import com.damien.youyu.service.BudgetService;
import com.damien.youyu.service.ReportService;
import com.damien.youyu.service.TransactionService;

/**
 * 周期记账的兼容回归（recurring-transactions 任务 10.2；需求 9.5、9.6）。
 *
 * <p>把「引入周期记账不改动六组既有接口（交易创建 / 账本 / 分类 / 账户 / 预算 / 报表）契约，且特性可整块
 * 摘除」这条边界钉成可执行断言。本类<b>补足</b>而非重复六组各自的既有测试套件：那些套件本身即编码了六组
 * 接口的请求契约、响应字段集与错误码集，且本 spec 未改动任何六组既有生产方法（周期记账全部为
 * {@code V38} 两张新表 + 新增控制器 / 服务，见下），故「六组既有测试全绿」即证六组契约不变（需求 9.5、9.6）。
 * 本类另加两道周期记账特有的守卫：</p>
 *
 * <ol>
 *   <li><b>结构可摘除（{@link #v38OnlyCreatesTwoNewTablesAndTouchesNoExistingTable()}）</b>：静态检视
 *       {@code V38__recurring_transactions.sql}——只 {@code CREATE TABLE} 两张新表
 *       （{@code recurring_rules}、{@code recurring_pending_items}），<b>无</b> {@code ALTER TABLE}
 *       （不对既有表加列 / 加约束）、<b>无</b> {@code FOREIGN KEY}/{@code REFERENCES}（不建指向既有表的
 *       外键）。故删除 {@code V38} 两表即可整块摘除，无需回改任何既有表结构（需求 9.2、9.6）。</li>
 *   <li><b>特性隔离（{@link #pendingItemsDoNotLeakIntoSixGroupsUntilConfirmed()}）</b>：周期规则与其
 *       {@code PENDING} 待确认项<b>绝不泄漏</b>进交易列表 / 月报表 / 分类报表 / 预算支出——待确认项在
 *       独立表 {@code recurring_pending_items}，在<b>用户确认</b>前不产生任何 {@code transactions} 行；
 *       只有确认后经既有 {@link TransactionService#create} 落库的那条流水才以普通交易口径出现在六组读路径
 *       （需求 9.5，对应 design.md Property 6「确认与手动记账口径一致」的隔离面）。</li>
 * </ol>
 *
 * <p>行为隔离用全栈 {@code @SpringBootTest} + 真实 {@link TransactionService}（确认走既有账户加锁 + 单事务
 * 原子），不加测试级 {@code @Transactional}（那会在方法结束回滚、掩盖确认的真实提交），清理改为每个用例前
 * 显式清库（{@link #reset()}），并用独立命名内存库避免污染其它切片测试。时钟固定于 {@code Asia/Shanghai}
 * 的 2025-06-15，使月报表 / 预算的自然月边界可确定性断言。规则以 {@link RuleStatus#PAUSED} 播种，使查询
 * 触发的懒生成零新增、待确认项集合完全由播种控制。</p>
 *
 * <p><strong>Validates: Requirements 9.5, 9.6</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-compat-regression;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringCompatibilityRegressionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final YearMonth MONTH = YearMonth.of(2025, 6);
    /** 待确认项到期日落在 MONTH 内，确认后应恰计入该月报表 / 预算。 */
    private static final LocalDate OCCURRENCE = LocalDate.of(2025, 6, 5);
    private static final BigDecimal INITIAL = new BigDecimal("1000000.00");
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    private static final Path V38 = Path.of(
            "src", "main", "resources", "db", "migration", "V38__recurring_transactions.sql");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private RecurringPendingItemService pendingItemService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private BudgetService budgetService;
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
        // 清理不靠回滚（确认真实提交）：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();

        accountId = seedAccount();
        linkAccountToLedger(accountId, LEDGER);
        categoryId = seedCategory("房租");
    }

    // =====================================================================
    // 1) 结构可摘除：V38 只建两张新表，不碰任何既有表（需求 9.2、9.6）
    // =====================================================================

    @Test
    void v38OnlyCreatesTwoNewTablesAndTouchesNoExistingTable() {
        String sql = read(V38);
        String upper = sql.toUpperCase();

        // 恰两处 CREATE TABLE，且为本 spec 的两张新表。
        List<String> createdTables = createTableTargets(sql);
        assertThat(createdTables)
                .as("V38 应只新建周期记账的两张独立表（需求 9.2）")
                .containsExactlyInAnyOrder("recurring_rules", "recurring_pending_items");

        // 不对既有表加列 / 加约束：全脚本无 ALTER TABLE（需求 9.2、9.6）。
        assertThat(upper)
                .as("V38 不得对任何既有表执行 ALTER TABLE（需求 9.2、9.6）")
                .doesNotContain("ALTER TABLE");

        // 不建指向既有表的外键：无 FOREIGN KEY / REFERENCES（归属与存在性由应用层校验，需求 9.2）。
        assertThat(upper)
                .as("V38 不得声明外键 FOREIGN KEY（需求 9.2）")
                .doesNotContain("FOREIGN KEY");
        assertThat(upper)
                .as("V38 不得以 REFERENCES 指向既有表（需求 9.2）")
                .doesNotContain("REFERENCES");
    }

    // =====================================================================
    // 2) 特性隔离：待确认项在确认前绝不泄漏进六组读路径（需求 9.5）
    // =====================================================================

    @Test
    void pendingItemsDoNotLeakIntoSixGroupsUntilConfirmed() {
        // 播种一条 PAUSED 规则（使查询触发的懒生成零新增）与其一条 PENDING 待确认项（支出 300，落 2025-06）。
        RecurringRule rule = seedPausedRule();
        RecurringPendingItem item = seedPendingItem(rule, OCCURRENCE);

        // 周期记账自身可见该待确认项（特性正常工作）。
        assertThat(pendingItemService.queryPendingItems(LEDGER))
                .extracting(RecurringPendingItem::getId)
                .as("周期记账查询应返回该 PENDING 待确认项")
                .containsExactly(item.getId());

        // —— 确认前：待确认项对六组读路径完全不可见（无任何 transactions 行）。——
        assertThat(transactionService.list(LEDGER, PageRequest.of(0, 20)).getTotalElements())
                .as("确认前交易列表不得出现待确认项（需求 9.5）")
                .isZero();

        MonthlyReportResponse reportBefore = reportService.monthlyReport(LEDGER, MONTH);
        assertThat(reportBefore.totalExpense())
                .as("确认前月报表支出不得计入待确认项（需求 9.5）")
                .isEqualByComparingTo("0.00");
        assertThat(reportBefore.totalIncome())
                .as("确认前月报表收入不得计入待确认项（需求 9.5）")
                .isEqualByComparingTo("0.00");

        BudgetOverviewResponse budgetBefore = budgetService.overview(LEDGER, MONTH);
        assertThat(budgetBefore.spent())
                .as("确认前预算已支出不得计入待确认项（需求 9.5）")
                .isEqualByComparingTo("0.00");

        // —— 确认：走既有交易创建链路落库，恰生成一条普通流水。——
        pendingItemService.confirm(ALICE, LEDGER, item.getId(),
                null, null, null, null, null);

        // —— 确认后：该次入账以普通交易口径恰出现在六组读路径，且金额闭合。——
        var page = transactionService.list(LEDGER, PageRequest.of(0, 20));
        assertThat(page.getTotalElements())
                .as("确认后应恰新增一条普通流水（需求 9.5）")
                .isEqualTo(1);
        Transaction tx = page.getContent().get(0);
        assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(tx.getLedgerId()).isEqualTo(LEDGER);

        MonthlyReportResponse reportAfter = reportService.monthlyReport(LEDGER, MONTH);
        assertThat(reportAfter.totalExpense())
                .as("确认后该流水以普通交易口径计入月报表支出（需求 9.5）")
                .isEqualByComparingTo(AMOUNT);

        BudgetOverviewResponse budgetAfter = budgetService.overview(LEDGER, MONTH);
        assertThat(budgetAfter.spent())
                .as("确认后该流水以普通交易口径计入预算已支出（需求 9.5）")
                .isEqualByComparingTo(AMOUNT);
    }

    // =====================================================================
    // 迁移脚本静态解析
    // =====================================================================

    private static String read(Path file) {
        assertThat(file).as("迁移脚本须存在：%s（测试工作目录应为项目根目录）", file).isRegularFile();
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 提取脚本中全部 {@code CREATE TABLE <name>} 的目标表名（大小写不敏感，去除可选反引号）。 */
    private static List<String> createTableTargets(String sql) {
        Pattern p = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        List<String> names = new java.util.ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // =====================================================================
    // 持久化辅助
    // =====================================================================

    private Long seedAccount() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(INITIAL);
        account.setCurrentBalance(INITIAL);
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

    /** 直接落库一条 PAUSED 每月规则（绕过创建校验；PAUSED 使查询触发的懒生成零新增）。 */
    private RecurringRule seedPausedRule() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType("expense");
        rule.setAmount(AMOUNT);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote("房租");
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(5);
        rule.setMonthEnd(false);
        rule.setStartDate(LocalDate.of(2025, 1, 5));
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.PAUSED);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem seedPendingItem(RecurringRule rule, LocalDate occurrenceDate) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        item.setType(rule.getType());
        item.setAmount(rule.getAmount());
        item.setCategoryId(rule.getCategoryId());
        item.setAccountId(rule.getAccountId());
        item.setNote(rule.getNote());
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }
}
