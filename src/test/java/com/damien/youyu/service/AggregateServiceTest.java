package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link AggregateService} 的隔离回归测试（关联需求 7.4、10.3；design.md Property 6 特性隔离）。
 *
 * <p>核心断言：「全部账本」聚合视图（分类与交易）<b>排除 AA 账本</b>，而个人 / 家庭（协作）账本
 * 原样纳入。使用 H2 + 真实 Repository，不使用任何桩。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AggregateServiceTest {

    private static final long USER = 1L;
    private static final YearMonth JUNE = YearMonth.of(2025, 6);

    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;

    private AggregateService service() {
        return new AggregateService(ledgerRepository, accountRepository,
                transactionRepository, categoryRepository, memberRepository);
    }

    // ---------------- (a) 「全部账本」交易聚合排除 AA ----------------

    @Test
    void allTransactionsInMonth_excludesAaLedger_includesPersonalAndCollaborative() {
        Ledger personal = ledger(USER, Ledger.TYPE_PERSONAL, "个人");
        Ledger family = ledger(USER, Ledger.TYPE_COLLABORATIVE, "家庭");
        Ledger aa = ledger(USER, Ledger.TYPE_AA, "旅行AA");
        member(personal.getId(), USER);
        member(family.getId(), USER);
        member(aa.getId(), USER);

        expense(personal.getId(), "100.00", dt("2025-06-05T12:00:00"));
        expense(family.getId(), "200.00", dt("2025-06-06T12:00:00"));
        // AA 账本内的 AA 支出与结算流水均不得进入「全部」聚合。
        aaExpense(aa.getId(), "999.00", dt("2025-06-07T12:00:00"));
        aaSettlement(aa.getId(), "50.00", dt("2025-06-08T12:00:00"));

        List<Transaction> all = service().allTransactionsInMonth(USER, JUNE);

        // 仅个人 + 家庭两笔，AA 账本整本被排除（需求 7.4 / Property 6）。
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Transaction::getLedgerId)
                .containsExactlyInAnyOrder(personal.getId(), family.getId());
        assertThat(all).noneMatch(t -> t.getLedgerId().equals(aa.getId()));
        assertThat(all).extracting(Transaction::getType)
                .doesNotContain(TransactionType.AA_EXPENSE, TransactionType.AA_SETTLEMENT);
    }

    @Test
    void allTransactionsInMonth_onlyAaLedger_returnsEmpty() {
        Ledger aa = ledger(USER, Ledger.TYPE_AA, "聚餐AA");
        member(aa.getId(), USER);
        aaExpense(aa.getId(), "88.00", dt("2025-06-09T12:00:00"));

        assertThat(service().allTransactionsInMonth(USER, JUNE)).isEmpty();
    }

    // ---------------- (a) 「全部账本」分类聚合排除 AA ----------------

    @Test
    void allCategories_excludesAaLedgerCategories() {
        Ledger personal = ledger(USER, Ledger.TYPE_PERSONAL, "个人");
        Ledger aa = ledger(USER, Ledger.TYPE_AA, "合租AA");
        member(personal.getId(), USER);
        member(aa.getId(), USER);
        Category personalCat = category(personal.getId(), "餐饮");
        Category aaCat = category(aa.getId(), "AA分类");

        List<Category> cats = service().allCategories(USER);

        assertThat(cats).extracting(Category::getId).contains(personalCat.getId());
        assertThat(cats).extracting(Category::getId).doesNotContain(aaCat.getId());
    }

    // ---------------- 账户口径：账户为用户级实体，反映真实资金，不受 AA 隔离影响 ----------------

    @Test
    void allAccounts_returnsUserOwnedAccounts_regardlessOfLedgerType() {
        // 账户独立于账本、归属用户，反映真实进出资金（需求 7.1）；此处仅验证无账户时安全返回空。
        assertThat(service().allAccounts(USER)).isEmpty();
    }

    // ---------------- 测试数据构造 ----------------

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }

    private Ledger ledger(long userId, String type, String name) {
        Ledger l = new Ledger();
        l.setUserId(userId);
        l.setName(name);
        l.setType(type);
        l.setSortOrder(0);
        l.setDefault(false);
        l.setCreatedAt(dt("2024-01-01T00:00:00"));
        l.setUpdatedAt(dt("2024-01-01T00:00:00"));
        return ledgerRepository.save(l);
    }

    private LedgerMember member(long ledgerId, long userId) {
        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledgerId);
        m.setUserId(userId);
        m.setRole(LedgerMember.ROLE_OWNER);
        m.setCreatedAt(dt("2024-01-01T00:00:00"));
        return memberRepository.save(m);
    }

    private Category category(long ledgerId, String name) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName(name);
        c.setCreatedAt(dt("2024-01-01T00:00:00"));
        c.setUpdatedAt(dt("2024-01-01T00:00:00"));
        return categoryRepository.save(c);
    }

    private void expense(long ledgerId, String amount, LocalDateTime when) {
        Transaction t = baseTx(ledgerId, TransactionType.EXPENSE, amount, when);
        t.setAccountId(1L);
        t.setCategoryId(1L);
        transactionRepository.save(t);
    }

    private void aaExpense(long ledgerId, String amount, LocalDateTime when) {
        Transaction t = baseTx(ledgerId, TransactionType.AA_EXPENSE, amount, when);
        t.setAccountId(1L);
        t.setCategoryId(1L);
        t.setPayerUserId(USER);
        transactionRepository.save(t);
    }

    private void aaSettlement(long ledgerId, String amount, LocalDateTime when) {
        Transaction t = baseTx(ledgerId, TransactionType.AA_SETTLEMENT, amount, when);
        transactionRepository.save(t);
    }

    private Transaction baseTx(long ledgerId, TransactionType type, String amount, LocalDateTime when) {
        Transaction t = new Transaction();
        t.setUserId(USER);
        t.setLedgerId(ledgerId);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setOccurredAt(when);
        t.setCreatedAt(when);
        t.setUpdatedAt(when);
        return t;
    }
}
