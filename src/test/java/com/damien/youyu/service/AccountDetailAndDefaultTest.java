package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 账户明细（需求 5）与快速记账默认账户（需求 7）的示例测试。
 *
 * <ul>
 *   <li>owner 查看账户明细：跨账本收支 + 转账全量。</li>
 *   <li>协作账本内其他成员查看共享账户明细：仅本账本内流水；不可见则 NOT_FOUND。</li>
 *   <li>默认账户：上一笔在该账本记账用的账户（仍可选则用之），否则可选集第一个；空集为 null。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountDetailAndDefaultTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long OWNER = 1L;
    private static final long MEMBER = 2L;
    private static final long LEDGER_L = 100L;
    private static final long LEDGER_M = 200L;

    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountLedgerRepository accountLedgerRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;

    private LedgerAccountResolver resolver() {
        return new LedgerAccountResolver(accountRepository, accountLedgerRepository);
    }

    private TransactionService txService() {
        Clock clock = Clock.fixed(T0, ZONE);
        return new TransactionService(transactionRepository, accountRepository, categoryRepository,
                resolver(), clock, new GrowthSettlementTrigger(null, clock),
                new BudgetReminderTrigger(null));
    }

    private Account account(long ownerId, String name, String balance) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(ownerId);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private void attach(Long accountId, long ledgerId, boolean visibleToOthers) {
        AccountLedger al = new AccountLedger();
        al.setAccountId(accountId);
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(visibleToOthers);
        al.setShowBalance(true);
        al.setCreatedAt(LocalDateTime.ofInstant(T0, ZONE));
        accountLedgerRepository.save(al);
    }

    private Category category(long ledgerId, CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    // ---------------- 账户明细 ----------------

    @Test
    void accountDetail_owner_seesAllReferencingAcrossLedgersAndTransfers() {
        Account a = account(OWNER, "现金", "1000.00");
        Account b = account(OWNER, "银行卡", "0.00");
        attach(a.getId(), LEDGER_L, true);
        attach(a.getId(), LEDGER_M, true);
        Category catL = category(LEDGER_L, CategoryKind.EXPENSE, "餐饮");
        Category catM = category(LEDGER_M, CategoryKind.EXPENSE, "餐饮");
        TransactionService tx = txService();

        tx.create(OWNER, LEDGER_L, "expense", new BigDecimal("10.00"), a.getId(), catL.getId(), null, "L");
        tx.create(OWNER, LEDGER_M, "expense", new BigDecimal("20.00"), a.getId(), catM.getId(), null, "M");
        tx.transfer(OWNER, a.getId(), b.getId(), new BigDecimal("30.00"), null, "转出");

        List<Transaction> detail = tx.listAccountDetail(OWNER, LEDGER_L, a.getId());

        // owner 看到全部引用：两个账本的支出 + 转账 = 3。
        assertThat(detail).hasSize(3);
        assertThat(detail).extracting(Transaction::getType)
                .containsExactlyInAnyOrder(TransactionType.EXPENSE, TransactionType.EXPENSE,
                        TransactionType.TRANSFER);
    }

    @Test
    void accountDetail_collaboratorVisible_seesOnlyThatLedgerFlows() {
        Account a = account(OWNER, "共享现金", "1000.00");
        Account b = account(OWNER, "银行卡", "0.00");
        attach(a.getId(), LEDGER_L, true);   // 暴露给协作成员
        attach(a.getId(), LEDGER_M, true);
        Category catL = category(LEDGER_L, CategoryKind.EXPENSE, "餐饮");
        Category catM = category(LEDGER_M, CategoryKind.EXPENSE, "餐饮");
        TransactionService tx = txService();

        tx.create(OWNER, LEDGER_L, "expense", new BigDecimal("10.00"), a.getId(), catL.getId(), null, "L");
        tx.create(OWNER, LEDGER_M, "expense", new BigDecimal("20.00"), a.getId(), catM.getId(), null, "M");
        tx.transfer(OWNER, a.getId(), b.getId(), new BigDecimal("30.00"), null, "转出");

        // 协作成员在账本 L 视角：只看到账本 L 内引用该账户的流水（不含账本 M、不含转账）。
        List<Transaction> detail = tx.listAccountDetail(MEMBER, LEDGER_L, a.getId());

        assertThat(detail).hasSize(1);
        assertThat(detail.get(0).getNote()).isEqualTo("L");
        assertThat(detail.get(0).getType()).isEqualTo(TransactionType.EXPENSE);
    }

    @Test
    void accountDetail_collaboratorNotVisible_notFound() {
        Account a = account(OWNER, "私有现金", "1000.00");
        attach(a.getId(), LEDGER_L, false); // 未暴露给他人

        TransactionService tx = txService();
        ApiException ex = catchThrowableOfType(
                () -> tx.listAccountDetail(MEMBER, LEDGER_L, a.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 默认账户 ----------------

    @Test
    void defaultAccount_returnsLastUsedInLedgerWhenStillSelectable() {
        Account a = account(OWNER, "现金", "1000.00");
        Account c = account(OWNER, "银行卡", "1000.00");
        attach(a.getId(), LEDGER_L, true);
        attach(c.getId(), LEDGER_L, true);
        Category cat = category(LEDGER_L, CategoryKind.EXPENSE, "餐饮");
        TransactionService tx = txService();

        tx.create(OWNER, LEDGER_L, "expense", new BigDecimal("10.00"), a.getId(), cat.getId(), null, null);
        // 上一笔记账用的是 c。
        tx.create(OWNER, LEDGER_L, "expense", new BigDecimal("20.00"), c.getId(), cat.getId(), null, null);

        Account def = tx.defaultAccountForEntry(OWNER, LEDGER_L);
        assertThat(def.getId()).isEqualTo(c.getId());
    }

    @Test
    void defaultAccount_noHistory_fallsBackToFirstSelectable() {
        Account a = account(OWNER, "现金", "1000.00");
        a.setSortOrder(0);
        accountRepository.save(a);
        Account c = account(OWNER, "银行卡", "1000.00");
        c.setSortOrder(1);
        accountRepository.save(c);
        attach(a.getId(), LEDGER_L, true);
        attach(c.getId(), LEDGER_L, true);

        Account def = txService().defaultAccountForEntry(OWNER, LEDGER_L);
        // 无记账历史 → 可选集排序第一（sortOrder 最小）。
        assertThat(def.getId()).isEqualTo(a.getId());
    }

    @Test
    void defaultAccount_emptySelectable_returnsNull() {
        assertThat(txService().defaultAccountForEntry(OWNER, LEDGER_L)).isNull();
    }
}
