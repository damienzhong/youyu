package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.api.dto.RecordSuggestionItem;
import com.damien.youyu.api.dto.RecordSuggestionResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link RecordSuggestionService#list} 的服务层测试（record-suggestion 任务 3.2，需求 1.1、2.5、6.6、7.1）。
 *
 * <p>沿用仓库/服务既有 {@code @SpringBootTest} + 真实 H2（{@code MODE=MySQL}）范式：真实
 * {@link TransactionRepository}/{@link CategoryRepository} 与真实只读事务，无 mock。窗口相对「今天」，
 * 为使窗口内的播种时间可复现，用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai}
 * 2025-06-15 12:00）覆盖 {@code TimeConfig} 的系统时钟——服务经该时钟算窗口起点
 * {@code today-29 00:00} 与终点 {@code today 23:59:59.999}，故播种一律落在当日噪声之外的确定时刻。</p>
 *
 * <p>本类不加测试级 {@code @Transactional}：{@code @SpringBootTest} 默认不开事务，
 * {@code repository.save} 各自提交，{@link #resetTables()} 每个用例前用原生 SQL 清三张表
 * （{@code transactions} 走原生删以连软删副本一并清掉，绕过 {@code @SQLRestriction}），
 * 用例间互不串数据。使用<b>独立命名</b>内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>覆盖任务 3.2 的六组口径：</p>
 * <ul>
 *   <li>去重后 &lt; 2 组 → 返回空列表（需求 7.1、6.6、2.5）；</li>
 *   <li>恰好 2 组 → 2 条；恰好 3 组 → 3 条（需求 1.1）；</li>
 *   <li>多于 3 组 → 截断为频率最高的 3 条（需求 1.1、3.4）；</li>
 *   <li>分类已删（无 {@code categories} 行）→ {@code categoryName}/{@code categoryIcon} 为 null（需求 4.5）；</li>
 *   <li>账户已删（无 {@code accounts} 行）→ 候选仍带原 {@code accountId}（服务不查账户表，需求 4.5）；</li>
 *   <li>不读取 {@code transaction_templates}：插入误导性模板行后候选逐字段不变（需求 2.6、8.1）。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-record-suggestion-svc;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(RecordSuggestionServiceTest.FixedClockConfig.class)
class RecordSuggestionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」= 2025-06-15；窗口 = [2025-05-17 00:00:00.000, 2025-06-15 23:59:59.999]。 */
    private static final Instant FIXED_INSTANT =
            LocalDateTime.of(2025, 6, 15, 12, 0).atZone(ZONE).toInstant();

    /**
     * 播种基准时刻：落在窗口内的<b>过去日</b>（今天=2025-06-15，基准取 06-10 中午），各形态以不同分钟错开
     * 使代表/近因确定。刻意不放在「今天」——服务已加「当天已记的形态当天不再推荐」排除，若基准落在今天，
     * 这些常规用例会被整体排除而返回空。今天已记的排除行为由 {@link #excludesShapesRecordedToday} 等专测覆盖。
     */
    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 10, 12, 0, 0);

    /** 今天中午（Asia/Shanghai）：用于构造「当天已记」的形态，验证其被排除。 */
    private static final LocalDateTime TODAY_NOON = LocalDateTime.of(2025, 6, 15, 12, 0, 0);

    private static final Long LEDGER = 5001L;
    private static final Long USER = 9001L;
    private static final Long ACCOUNT = 7001L;

    @Autowired
    private RecordSuggestionService service;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        // 走原生 SQL：transactions 带 @SQLRestriction，deleteAll 只删未软删行；这里连软删副本一并清掉。
        jdbcTemplate.update("DELETE FROM transaction_templates");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ---- <2 组返回空（需求 2.5、6.6、7.1） ----

    @Test
    void returnsEmptyWhenFewerThanTwoShapes() {
        // 仅一个形态（同一 type/cat/acct/amount/note 重复 3 笔）→ 去重后 1 组 < 2 → 空。
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE.minusMinutes(2));

        RecordSuggestionResponse response = service.list(LEDGER);

        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoHistory() {
        assertThat(service.list(LEDGER).suggestions()).isEmpty();
    }

    // ---- 恰好 2 组返回 2 条，按频率降序（需求 1.1、3.2） ----

    @Test
    void returnsExactlyTwoOrderedByFrequency() {
        // 形态高频：expense 35.00 午餐 ×2；形态低频：expense 6.00 地铁 ×1。
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE.minusMinutes(2));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).amount()).isEqualByComparingTo("35.00");
        assertThat(items.get(0).note()).isEqualTo("午餐");
        assertThat(items.get(1).amount()).isEqualByComparingTo("6.00");
        assertThat(items.get(1).note()).isEqualTo("地铁");
        // 类型恒为小写编码（需求 6.1 契约）。
        assertThat(items).extracting(RecordSuggestionItem::type).containsOnly("expense");
    }

    // ---- 恰好 3 组返回 3 条（需求 1.1） ----

    @Test
    void returnsExactlyThreeShapes() {
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE.minusMinutes(1));
        persistRecord(TransactionType.INCOME, "100.00", 6003L, "红包", BASE.minusMinutes(2));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).hasSize(3);
        assertThat(items).extracting(RecordSuggestionItem::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(
                        new BigDecimal("35.00"), new BigDecimal("6.00"), new BigDecimal("100.00"));
    }

    // ---- 多于 3 组截断为频率最高的 3 条（需求 1.1、3.4） ----

    @Test
    void truncatesToTopThreeByFrequency() {
        // 四个形态，频率 4/3/2/1；最低频（amount 1.00）应被截掉。
        seedShape(TransactionType.EXPENSE, "35.00", 6001L, "午餐", 4);
        seedShape(TransactionType.EXPENSE, "6.00", 6002L, "地铁", 3);
        seedShape(TransactionType.EXPENSE, "16.00", 6003L, "奶茶", 2);
        seedShape(TransactionType.EXPENSE, "1.00", 6004L, "口香糖", 1);

        var items = service.list(LEDGER).suggestions();

        assertThat(items).hasSize(3);
        assertThat(items).extracting(RecordSuggestionItem::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("35.00"), new BigDecimal("6.00"), new BigDecimal("16.00"));
        assertThat(items).extracting(RecordSuggestionItem::note).doesNotContain("口香糖");
    }

    // ---- 分类已删 → categoryName/categoryIcon 为 null（需求 4.5） ----

    @Test
    void nullsCategoryNameWhenCategoryDeleted() {
        // 存在的分类（餐饮/food）；不存在的分类（模拟已删）。两个形态达渲染门槛。
        Long existingCat = persistCategory(CategoryKind.EXPENSE, "餐饮", "food");
        Long ghostCat = existingCat + 90000L; // 无对应 categories 行
        persistRecord(TransactionType.EXPENSE, "35.00", existingCat, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "35.00", existingCat, "午餐", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "6.00", ghostCat, "地铁", BASE.minusMinutes(2));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).hasSize(2);
        RecordSuggestionItem existing = itemByCategoryId(items, existingCat);
        RecordSuggestionItem deleted = itemByCategoryId(items, ghostCat);
        assertThat(existing.categoryName()).isEqualTo("餐饮");
        assertThat(existing.categoryIcon()).isEqualTo("food");
        assertThat(deleted.categoryName()).isNull();
        assertThat(deleted.categoryIcon()).isNull();
        // 分类已删不影响候选生成：categoryId 照带。
        assertThat(deleted.categoryId()).isEqualTo(ghostCat);
    }

    // ---- 账户已删仍带 accountId（服务不查 accounts 表，需求 4.5） ----

    @Test
    void carriesAccountIdEvenWhenAccountDeleted() {
        // accounts 表从不被服务查询，故引用一个不存在的账户 id，候选仍应带上它。
        Long ghostAccount = 88888L;
        persistRecordWithAccount(TransactionType.EXPENSE, "35.00", 6001L, ghostAccount, "午餐", BASE);
        persistRecordWithAccount(TransactionType.EXPENSE, "6.00", 6002L, ghostAccount, "地铁", BASE.minusMinutes(1));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).hasSize(2);
        assertThat(items).extracting(RecordSuggestionItem::accountId).containsOnly(ghostAccount);
    }

    // ---- 不读取 transaction_templates（需求 2.6、8.1） ----

    @Test
    void ignoresTransactionTemplates() {
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE.minusMinutes(1));

        // 无模板时的基线结果。
        var baseline = service.list(LEDGER).suggestions();
        assertThat(baseline).hasSize(2);

        // 插入若干误导性模板：与真实历史不同的形态（金额 999、独特备注）。若服务读了模板，结果会变。
        insertTemplate("模板午餐", "expense", "999.00", 6001L, "模板不该出现A");
        insertTemplate("模板地铁", "income", "888.00", 6002L, "模板不该出现B");
        insertTemplate("模板红包", "expense", "777.00", 6003L, "模板不该出现C");

        var afterTemplates = service.list(LEDGER).suggestions();

        // 模板数据不得改变候选：条数、字段逐条不变。
        assertThat(afterTemplates).hasSize(2);
        assertThat(afterTemplates).extracting(RecordSuggestionItem::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("35.00"), new BigDecimal("6.00"));
        assertThat(afterTemplates).extracting(RecordSuggestionItem::note)
                .doesNotContain("模板不该出现A", "模板不该出现B", "模板不该出现C");
        assertThat(afterTemplates).extracting(RecordSuggestionItem::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .doesNotContain(new BigDecimal("999.00"), new BigDecimal("888.00"), new BigDecimal("777.00"));
    }

    // ---- 「当天已记」的形态当天不再推荐（issue #3） ----

    @Test
    void excludesShapesRecordedToday() {
        // 两个过去形态达门槛：午餐×2、地铁×1。
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE.minusMinutes(2));
        // 今天已记的高频形态：奶茶×3（今天）——即便频次最高，也应被当天排除。
        persistRecord(TransactionType.EXPENSE, "16.00", 6003L, "奶茶", TODAY_NOON);
        persistRecord(TransactionType.EXPENSE, "16.00", 6003L, "奶茶", TODAY_NOON.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "16.00", 6003L, "奶茶", TODAY_NOON.minusMinutes(2));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).extracting(RecordSuggestionItem::note)
                .containsExactlyInAnyOrder("午餐", "地铁")
                .doesNotContain("奶茶");
    }

    @Test
    void excludesShapeEvenWhenItAlsoHasPastOccurrences() {
        // 午餐：过去 2 笔 + 今天 1 笔 → 因今天已记一笔，整条当天排除（近因落在今天）。
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE);
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "35.00", 6001L, "午餐", TODAY_NOON.minusHours(3));
        // 另两个纯过去形态保证仍有候选：地铁×2、通讯×1。
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE);
        persistRecord(TransactionType.EXPENSE, "6.00", 6002L, "地铁", BASE.minusMinutes(1));
        persistRecord(TransactionType.EXPENSE, "50.00", 6004L, "通讯", BASE.minusMinutes(2));

        var items = service.list(LEDGER).suggestions();

        assertThat(items).extracting(RecordSuggestionItem::note)
                .containsExactlyInAnyOrder("地铁", "通讯")
                .doesNotContain("午餐");
    }

    // ---------------- 测试基础设施 ----------------

    /** 播种一个形态的 {@code count} 笔重复流水（各笔 occurredAt 递减错开，保证代表/近因确定）。 */
    private void seedShape(TransactionType type, String amount, Long categoryId, String note, int count) {
        for (int i = 0; i < count; i++) {
            persistRecord(type, amount, categoryId, note, BASE.minusSeconds(i));
        }
    }

    private Long persistRecord(TransactionType type, String amount, Long categoryId, String note,
                               LocalDateTime occurredAt) {
        return persistRecordWithAccount(type, amount, categoryId, ACCOUNT, note, occurredAt);
    }

    private Long persistRecordWithAccount(TransactionType type, String amount, Long categoryId,
                                          Long accountId, String note, LocalDateTime occurredAt) {
        Transaction t = new Transaction();
        t.setUserId(USER);
        t.setLedgerId(LEDGER);
        t.setCreatedBy(USER);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setNote(note);
        t.setOccurredAt(occurredAt);
        t.setCreatedAt(BASE);
        t.setUpdatedAt(BASE);
        return transactionRepository.save(t).getId();
    }

    /** 落一条分类（id 由 IDENTITY 生成），返回其生成 id。 */
    private Long persistCategory(CategoryKind kind, String name, String icon) {
        Category c = new Category();
        c.setUserId(USER);
        c.setLedgerId(LEDGER);
        c.setKind(kind);
        c.setName(name);
        c.setIcon(icon);
        c.setCreatedAt(BASE);
        c.setUpdatedAt(BASE);
        return categoryRepository.save(c).getId();
    }

    private void insertTemplate(String name, String type, String amount, Long categoryId, String note) {
        jdbcTemplate.update(
                "INSERT INTO transaction_templates "
                        + "(user_id, ledger_id, name, type, amount, account_id, category_id, note, sort_order, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                USER, LEDGER, name, type, new BigDecimal(amount), ACCOUNT, categoryId, note, 0, BASE, BASE);
    }

    private static RecordSuggestionItem itemByCategoryId(java.util.List<RecordSuggestionItem> items, Long categoryId) {
        return items.stream()
                .filter(it -> categoryId.equals(it.categoryId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 categoryId=" + categoryId + " 的候选"));
    }

    /** {@code @Primary} 固定时钟，覆盖 {@code TimeConfig} 的系统时钟，使窗口相对「今天」可复现。 */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZONE);
        }
    }
}
