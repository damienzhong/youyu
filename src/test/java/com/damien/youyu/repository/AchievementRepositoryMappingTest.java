package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.AchievementNotice;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;

/**
 * 成就系统数据层的映射与查询验证（H2，表由 Hibernate 依实体生成，Flyway 关闭）。
 *
 * <p>沿用 {@link GrowthRepositoryMappingTest} 的范式（{@code @DataJpaTest} + 真实 H2 +
 * 真实仓储，无 mock）。覆盖任务 1.10 的四组口径：</p>
 * <ul>
 *   <li>{@code achievement_notices} 恰好 4 列、实体↔表结构一致；以显式 {@code userId} 保存后
 *       可按主键读回（主键刻意不带 {@code @GeneratedValue}，库不改写它）；
 *       {@code deleteByUserId} 无行时影响行数 0 且不抛错（需求 10.1、10.4、11.3）；</li>
 *   <li>{@code maxBadgeEventId} 无 {@code BADGE} 行时返回 0（{@code COALESCE} 而非 {@code null}）、
 *       {@code countPendingBadgeEvents} 在 cursor 等于最大 id 时返回 0（需求 5.5、5.6）；</li>
 *   <li>{@code countEditorsOfOwnedLedgers} 按成员行计数、三类排除（需求 3.3、3.4）；</li>
 *   <li>{@code countTravelExpenses} 的父/子分类各计 1 次与五类排除（需求 3.9、3.10）。</li>
 * </ul>
 *
 * <p>写入播报游标行刻意走 {@link TestEntityManager#persist}，不用
 * {@code AchievementNoticeRepository} 继承来的 {@code save}：该仓储的契约是「不提供任何单行写入
 * 方法」，游标推进只能走服务层那条 ODKU + {@code GREATEST}。测试里用 {@code save} 会给后来者
 * 一个「这条路是通的」的错误示范。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AchievementRepositoryMappingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AchievementNoticeRepository noticeRepository;

    @Autowired
    private GrowthEventRepository growthEventRepository;

    @Autowired
    private LedgerMemberRepository ledgerMemberRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** 让后续读取一定回库，避免持久化上下文里的旧实体掩盖实际的映射与写入效果。 */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    @SuppressWarnings("unchecked")
    private List<String> columnNamesOf(String table) {
        return em.getEntityManager()
                .createNativeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ?1 ORDER BY COLUMN_NAME")
                .setParameter(1, table)
                .getResultList();
    }

    // ---- 播报游标：实体↔表结构、显式主键读回、无行删除（需求 10.1、10.4、11.3） ----

    @Test
    void achievementNoticesTableHasExactlyFourMappedColumns() {
        assertThat(columnNamesOf("ACHIEVEMENT_NOTICES")).containsExactlyInAnyOrder(
                "USER_ID", "LAST_NOTIFIED_EVENT_ID", "CREATED_AT", "UPDATED_AT");
    }

    @Test
    void achievementNoticePersistsWithExplicitUserIdAndRoundTripsAllColumns() {
        AchievementNotice notice = new AchievementNotice();
        notice.setUserId(8001L);
        notice.setLastNotifiedEventId(42L);
        notice.setCreatedAt(BASE);
        notice.setUpdatedAt(BASE.plusMinutes(5));
        em.persist(notice);
        flushAndClear();

        // 主键即写入的 userId（不带 @GeneratedValue，库不改写它）
        AchievementNotice reloaded = noticeRepository.findById(8001L).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(8001L);
        assertThat(reloaded.getLastNotifiedEventId()).isEqualTo(42L);
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(BASE.plusMinutes(5));

        // 主键落库即写入值，不是数据库分配的代理键
        Object rawId = em.getEntityManager()
                .createNativeQuery("SELECT user_id FROM achievement_notices WHERE user_id = ?1")
                .setParameter(1, 8001L)
                .getSingleResult();
        assertThat(((Number) rawId).longValue()).isEqualTo(8001L);
    }

    @Test
    void noticeFindByIdReturnsEmptyWhenNoRow() {
        // 无游标行不是异常状态：服务层按游标取值 0 处理（需求 5.3）
        assertThat(noticeRepository.findById(8002L)).isEmpty();
    }

    @Test
    void noticeDeleteByUserIdAffectsZeroRowsWhenNoRowAndDoesNotThrow() {
        assertThatCode(() -> assertThat(noticeRepository.deleteByUserId(123456L)).isZero())
                .doesNotThrowAnyException();
    }

    @Test
    void noticeDeleteByUserIdRemovesOnlyTargetUsersRow() {
        AchievementNotice target = new AchievementNotice();
        target.setUserId(8003L);
        target.setLastNotifiedEventId(7L);
        target.setCreatedAt(BASE);
        target.setUpdatedAt(BASE);
        em.persist(target);
        AchievementNotice other = new AchievementNotice();
        other.setUserId(8004L);
        other.setLastNotifiedEventId(9L);
        other.setCreatedAt(BASE);
        other.setUpdatedAt(BASE);
        em.persist(other);
        flushAndClear();

        assertThat(noticeRepository.deleteByUserId(8003L)).isEqualTo(1);
        flushAndClear();

        assertThat(noticeRepository.findById(8003L)).isEmpty();
        AchievementNotice untouched = noticeRepository.findById(8004L).orElseThrow();
        assertThat(untouched.getLastNotifiedEventId()).isEqualTo(9L);
        assertThat(untouched.getCreatedAt()).isEqualTo(BASE);
        assertThat(untouched.getUpdatedAt()).isEqualTo(BASE);
    }

    // ---- 待播报三查询（需求 5.5、5.6） ----

    @Test
    void maxBadgeEventIdReturnsZeroWhenNoBadgeRows() {
        // 完全没有成长事件的用户
        assertThat(growthEventRepository.maxBadgeEventId(9001L)).isZero();

        // 有成长事件但没有 BADGE 行：上界同样为 0（COALESCE 而非 null）
        long userId = 9002L;
        growthEventRepository.save(newEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        growthEventRepository.save(
                newEvent(userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:2025-06-01", 5));
        growthEventRepository.save(
                newEvent(userId, GrowthEventType.SAVING_MONTH, "SAVING_MONTH:2025-05", 0));
        flushAndClear();

        assertThat(growthEventRepository.maxBadgeEventId(userId)).isZero();
        assertThat(growthEventRepository.countPendingBadgeEvents(userId, 0L)).isZero();
        assertThat(growthEventRepository.findPendingBadgeEvents(userId, 0L, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void countPendingBadgeEventsReturnsZeroWhenCursorEqualsMaxId() {
        long userId = 9003L;
        GrowthEvent first = growthEventRepository.save(
                newEvent(userId, GrowthEventType.BADGE, "BADGE:FIRST_RECORD", 0));
        GrowthEvent second = growthEventRepository.save(
                newEvent(userId, GrowthEventType.BADGE, "BADGE:RECORD_10", 0));
        // 混入非 BADGE 行：不参与上界与待播报计数
        growthEventRepository.save(newEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 5));
        flushAndClear();

        long maxId = growthEventRepository.maxBadgeEventId(userId);
        assertThat(maxId).isEqualTo(second.getId());

        // 游标等于最大 id：无待播报
        assertThat(growthEventRepository.countPendingBadgeEvents(userId, maxId)).isZero();
        assertThat(growthEventRepository.findPendingBadgeEvents(userId, maxId, PageRequest.of(0, 10)))
                .isEmpty();

        // 游标 0 / 中间值：按 id 升序返回，条数为截断前条数
        assertThat(growthEventRepository.countPendingBadgeEvents(userId, 0L)).isEqualTo(2L);
        assertThat(growthEventRepository.findPendingBadgeEvents(userId, 0L, PageRequest.of(0, 10)))
                .extracting(GrowthEvent::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(growthEventRepository.countPendingBadgeEvents(userId, first.getId())).isEqualTo(1L);
        assertThat(growthEventRepository.findPendingBadgeEvents(
                userId, first.getId(), PageRequest.of(0, 10)))
                .extracting(GrowthEvent::getId)
                .containsExactly(second.getId());

        // 用户隔离：别人的 BADGE 行既不进上界也不进待播报
        assertThat(growthEventRepository.maxBadgeEventId(9004L)).isZero();
        assertThat(growthEventRepository.countPendingBadgeEvents(9004L, 0L)).isZero();
    }

    // ---- 协作成员数（需求 3.3、3.4） ----

    @Test
    void countEditorsOfOwnedLedgersCountsMemberRowsAndExcludesThreeCases() {
        long owner = 9101L;
        long collaborator = 9102L;
        long stranger = 9103L;

        Long ownedA = newLedger(owner, "我的账本 A").getId();
        Long ownedB = newLedger(owner, "我的账本 B").getId();
        Long othersLedger = newLedger(stranger, "别人的账本").getId();

        // 计入：同一个人以 EDITOR 加入本人 2 个账本 —— 按成员行计数，得 2
        newMember(ownedA, collaborator, LedgerMember.ROLE_EDITOR);
        newMember(ownedB, collaborator, LedgerMember.ROLE_EDITOR);

        // 排除 1：OWNER 行（账本创建者自己不算协作成员）
        newMember(ownedA, owner, LedgerMember.ROLE_OWNER);
        newMember(ownedB, owner, LedgerMember.ROLE_OWNER);
        // 排除 2：本人行（即便角色写成 EDITOR，本人也不是自己的协作成员）
        //         唯一约束 uk_ledger_member(ledger_id, user_id) 使本人在同一账本只能有一行，
        //         故这行放在 othersLedger 之外的第三个自有账本上。
        Long ownedC = newLedger(owner, "我的账本 C").getId();
        newMember(ownedC, owner, LedgerMember.ROLE_EDITOR);
        // 排除 3：本人作为 EDITOR 加入他人账本（账本归属只认 ledgers.user_id）
        newMember(othersLedger, owner, LedgerMember.ROLE_EDITOR);
        // 他人账本上他人的 EDITOR 行同样与本人无关
        newMember(othersLedger, collaborator, LedgerMember.ROLE_EDITOR);
        newMember(othersLedger, stranger, LedgerMember.ROLE_OWNER);
        flushAndClear();

        assertThat(ledgerMemberRepository.countEditorsOfOwnedLedgers(owner)).isEqualTo(2L);

        // 「我加入了别人的账本」不为我自己的协作数贡献；stranger 自己的账本上有 1 名 EDITOR（owner）
        // 与 1 名 EDITOR（collaborator），故 stranger 得 2
        assertThat(ledgerMemberRepository.countEditorsOfOwnedLedgers(stranger)).isEqualTo(2L);

        // 没有任何账本的用户得 0
        assertThat(ledgerMemberRepository.countEditorsOfOwnedLedgers(9104L)).isZero();
    }

    // ---- 旅行记账笔数（需求 3.9、3.10） ----

    @Test
    void countTravelExpensesCountsTravelTreeOnceAndExcludesFiveCases() {
        long userId = 9201L;
        long ledgerId = newLedger(userId, "旅行账本").getId();

        Long travelParent = newCategory(userId, ledgerId, null, CategoryKind.EXPENSE, "旅行").getId();
        Long travelChild = newCategory(userId, ledgerId, travelParent, CategoryKind.EXPENSE, "机票").getId();
        // 「旅行保险」是另一棵树的父分类：名称含「旅行」但逐字符不等，不该计入
        Long travelInsurance = newCategory(userId, ledgerId, null, CategoryKind.EXPENSE, "旅行保险").getId();

        // 计入：父分类交易 + 子分类交易，各 1 次
        newTx(userId, ledgerId, travelParent, TransactionType.EXPENSE, "100.00", BASE.plusDays(1), null);
        newTx(userId, ledgerId, travelChild, TransactionType.EXPENSE, "200.00", BASE.plusDays(2), null);

        // 排除 1：「旅行保险」分类（逐字符相等，不做包含匹配）
        newTx(userId, ledgerId, travelInsurance, TransactionType.EXPENSE, "300.00", BASE.plusDays(3), null);
        // 排除 2：软删（deleted_at 非空）
        newTx(userId, ledgerId, travelParent, TransactionType.EXPENSE, "400.00", BASE.plusDays(4),
                BASE.plusDays(5));
        // 排除 3：ledger_id 为 NULL
        newTx(userId, null, travelChild, TransactionType.EXPENSE, "500.00", BASE.plusDays(6), null);
        // 排除 4：income 类型
        newTx(userId, ledgerId, travelParent, TransactionType.INCOME, "600.00", BASE.plusDays(7), null);
        // 排除 5：他人记账（归属只认 created_by）
        newTx(9202L, ledgerId, travelParent, TransactionType.EXPENSE, "700.00", BASE.plusDays(8), null);
        flushAndClear();

        assertThat(transactionRepository.countTravelExpenses(userId)).isEqualTo(2L);
        // 他人只有那 1 笔
        assertThat(transactionRepository.countTravelExpenses(9202L)).isEqualTo(1L);
        // 无任何交易的用户得 0
        assertThat(transactionRepository.countTravelExpenses(9203L)).isZero();
    }

    @Test
    void countTravelExpensesCountsAtMostOncePerTransactionWhenBothLevelsMatch() {
        // 父与子同名「旅行」：c 与 p 两侧条件同时成立。与 categories 是 1:1 join，
        // 因此该交易在 COUNT(*) 里仍只出现 1 行（需求 3.9 末句）。
        long userId = 9301L;
        long ledgerId = newLedger(userId, "同名分类账本").getId();
        Long parent = newCategory(userId, ledgerId, null, CategoryKind.EXPENSE, "旅行").getId();
        Long childSameName = newCategory(userId, ledgerId, parent, CategoryKind.EXPENSE, "旅行").getId();

        newTx(userId, ledgerId, childSameName, TransactionType.EXPENSE, "10.00", BASE.plusDays(1), null);
        flushAndClear();

        assertThat(transactionRepository.countTravelExpenses(userId)).isEqualTo(1L);
    }

    // ---- 夹具 ----

    private GrowthEvent newEvent(long userId, String type, String key, int exp) {
        GrowthEvent e = new GrowthEvent();
        e.setUserId(userId);
        e.setEventType(type);
        e.setEventKey(key);
        e.setExpAmount(exp);
        e.setCreatedAt(BASE);
        return e;
    }

    private Ledger newLedger(long userId, String name) {
        Ledger l = new Ledger();
        l.setUserId(userId);
        l.setName(name);
        l.setType(Ledger.TYPE_COLLABORATIVE);
        l.setCreatedAt(BASE);
        l.setUpdatedAt(BASE);
        em.persist(l);
        return l;
    }

    private LedgerMember newMember(Long ledgerId, long userId, String role) {
        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledgerId);
        m.setUserId(userId);
        m.setRole(role);
        m.setCreatedAt(BASE);
        return ledgerMemberRepository.save(m);
    }

    private Category newCategory(long userId, long ledgerId, Long parentId, CategoryKind kind, String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setLedgerId(ledgerId);
        c.setParentId(parentId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(BASE);
        c.setUpdatedAt(BASE);
        em.persist(c);
        em.flush();
        return c;
    }

    private Transaction newTx(Long createdBy, Long ledgerId, Long categoryId, TransactionType type,
            String amount, LocalDateTime occurredAt, LocalDateTime deletedAt) {
        Transaction t = new Transaction();
        t.setUserId(createdBy);
        t.setLedgerId(ledgerId);
        t.setCreatedBy(createdBy);
        t.setCategoryId(categoryId);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(occurredAt);
        t.setCreatedAt(occurredAt);
        t.setUpdatedAt(occurredAt);
        t.setDeletedAt(deletedAt);
        return transactionRepository.save(t);
    }
}
