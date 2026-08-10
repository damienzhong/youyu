package com.damien.youyu.service.aa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * {@link AaExpenseService#create} 的示例与边界单元测试（H2 + 真实 Repository，固定 {@link Clock}）。
 *
 * <p>验证：付款人为本人扣账户全额、付款人非本人不动本人账户、均分余数校正、自定义校验、分摊守恒、
 * 只读 / 越权 / 字段校验的零副作用（需求 3.1、3.2、3.3、3.4、3.5、3.7、4.5、7.1、9.4、9.5）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AaExpenseServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long CAROL = 3L;
    private static final long OUTSIDER = 9L;

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private TransactionSplitRepository splitRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;
    @Autowired
    private AaSettlementRepository settlementRepository;

    private AaExpenseService service() {
        Clock clock = Clock.fixed(T0, ZONE);
        return new AaExpenseService(transactionRepository, splitRepository, accountRepository,
                categoryRepository, ledgerRepository, memberRepository, settlementRepository, clock);
    }

    // ---------------- fixtures ----------------

    private Ledger aaLedger(long ownerId, boolean archived) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("旅行 AA");
        l.setType(Ledger.TYPE_AA);
        l.setSortOrder(0);
        l.setDefault(false);
        if (archived) {
            l.setArchivedAt(now);
        }
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return ledgerRepository.save(l);
    }

    private void member(long ledgerId, long userId, String role) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledgerId);
        m.setUserId(userId);
        m.setRole(role);
        m.setCreatedAt(now);
        memberRepository.save(m);
    }

    private Account account(long userId, String name, String balance) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private Category category(long ledgerId, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    /** 建一个 Alice(owner)、Bob、Carol 三人 AA 账本，返回账本 id。 */
    private Ledger threeMemberLedger(boolean archived) {
        Ledger l = aaLedger(ALICE, archived);
        member(l.getId(), ALICE, LedgerMember.ROLE_OWNER);
        member(l.getId(), BOB, LedgerMember.ROLE_EDITOR);
        member(l.getId(), CAROL, LedgerMember.ROLE_EDITOR);
        return l;
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    private Map<Long, BigDecimal> sharesOf(Long txId) {
        java.util.Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (TransactionSplit s : splitRepository.findByTransactionId(txId)) {
            out.put(s.getParticipantUserId(), s.getShareAmount());
        }
        return out;
    }

    /** 在账本内落一条未撤销结算，用于验证「已涉结算拒删/拒改」。 */
    private AaSettlement settlement(long ledgerId, long from, long to, String amount) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        AaSettlement s = new AaSettlement();
        s.setLedgerId(ledgerId);
        s.setFromUserId(from);
        s.setToUserId(to);
        s.setAmount(new BigDecimal(amount));
        s.setSettledBy(from);
        s.setSettledAt(now);
        return settlementRepository.save(s);
    }

    /** 某笔 AA 支出（含已软删除）是否仍可通过常规查询检索到。 */
    private boolean expenseVisible(long ledgerId, Long txId) {
        return transactionRepository.findByLedgerId(ledgerId).stream()
                .anyMatch(t -> t.getId().equals(txId));
    }

    // ---------------- 付款人为本人：扣账户全额 ----------------

    @Test
    void create_payerIsSelf_deductsFullPaidAmountAndSplits() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        assertThat(tx.getId()).isNotNull();
        assertThat(tx.getType()).isEqualTo(TransactionType.AA_EXPENSE);
        assertThat(tx.getPayerUserId()).isEqualTo(ALICE);
        assertThat(tx.getAccountId()).isEqualTo(acc.getId());
        assertThat(tx.getLedgerId()).isEqualTo(l.getId());
        assertThat(tx.getCreatedBy()).isEqualTo(ALICE);
        // 实付全额扣款（需求 3.2、7.1）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        // 分摊守恒（Property 1）：三人均分 90 → 各 30。
        Map<Long, BigDecimal> shares = sharesOf(tx.getId());
        assertThat(shares).hasSize(3);
        assertThat(shares.get(ALICE)).isEqualByComparingTo("30.00");
        assertThat(shares.get(BOB)).isEqualByComparingTo("30.00");
        assertThat(shares.get(CAROL)).isEqualByComparingTo("30.00");
    }

    @Test
    void create_evenSplit_correctsRemainderInCents() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "100.00");

        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("10.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        Map<Long, BigDecimal> shares = sharesOf(tx.getId());
        // 1000 分 / 3 = 333 余 1 → 首位 +1：3.34 / 3.33 / 3.33，合计 10.00。
        assertThat(shares.get(ALICE)).isEqualByComparingTo("3.34");
        assertThat(shares.get(BOB)).isEqualByComparingTo("3.33");
        assertThat(shares.get(CAROL)).isEqualByComparingTo("3.33");
        BigDecimal sum = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("10.00");
    }

    // ---------------- 付款人非本人：不动本人账户 ----------------

    @Test
    void create_payerIsOther_doesNotTouchCurrentUserAccount() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        // 当前用户 Alice 记账，但付款人是 Bob。Alice 有账户，不应被扣。
        Account aliceAcc = account(ALICE, "现金", "500.00");

        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("60.00"), cat.getId(),
                BOB, aliceAcc.getId(), null, "Bob 付", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        assertThat(tx.getPayerUserId()).isEqualTo(BOB);
        // 付款人非本人 → 不记付款账户、不动本人账户（需求 3.7、7.1）。
        assertThat(tx.getAccountId()).isNull();
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("500.00");
        assertThat(sharesOf(tx.getId())).hasSize(3);
    }

    // ---------------- 自定义分摊 ----------------

    @Test
    void create_customSplit_valid_persistsShares() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("100.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_CUSTOM,
                List.of(ALICE, BOB, CAROL),
                Map.of(ALICE, new BigDecimal("50.00"), BOB, new BigDecimal("30.00"),
                        CAROL, new BigDecimal("20.00")));

        Map<Long, BigDecimal> shares = sharesOf(tx.getId());
        assertThat(shares.get(ALICE)).isEqualByComparingTo("50.00");
        assertThat(shares.get(BOB)).isEqualByComparingTo("30.00");
        assertThat(shares.get(CAROL)).isEqualByComparingTo("20.00");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("200.00");
    }

    @Test
    void create_customSplit_sumMismatch_rejectedWithZeroSideEffect() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, l.getId(),
                new BigDecimal("100.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_CUSTOM, List.of(ALICE, BOB, CAROL),
                Map.of(ALICE, new BigDecimal("50.00"), BOB, new BigDecimal("30.00"),
                        CAROL, new BigDecimal("10.00"))), // 合计 90 ≠ 100
                ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_SPLIT_MISMATCH");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        assertThat(transactionRepository.findByLedgerId(l.getId())).isEmpty();
    }

    // ---------------- 只读 / 越权 / 字段校验 ----------------

    @Test
    void create_archivedLedger_rejected() {
        Ledger l = threeMemberLedger(true);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, l.getId(),
                new BigDecimal("30.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB, CAROL), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
    }

    @Test
    void create_nonMemberCurrentUser_returnsNotFound() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(OUTSIDER, "现金", "300.00");

        ApiException ex = catchThrowableOfType(() -> service().create(OUTSIDER, l.getId(),
                new BigDecimal("30.00"), cat.getId(), OUTSIDER, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(OUTSIDER), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void create_payerSelfMissingAccount_rejectedWithFieldRequired() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, l.getId(),
                new BigDecimal("30.00"), cat.getId(), ALICE, null, null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB, CAROL), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("FIELD_REQUIRED");
        assertThat(ex.getField()).isEqualTo("payerAccountId");
        assertThat(transactionRepository.findByLedgerId(l.getId())).isEmpty();
    }

    @Test
    void create_participantNotMember_rejected() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, l.getId(),
                new BigDecimal("30.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE, OUTSIDER), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_PARTICIPANT_INVALID");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
    }

    @Test
    void create_nonAaLedger_returnsNotFound() {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger personal = new Ledger();
        personal.setUserId(ALICE);
        personal.setName("个人");
        personal.setType(Ledger.TYPE_PERSONAL);
        personal.setSortOrder(0);
        personal.setCreatedAt(now);
        personal.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(personal);
        member(saved.getId(), ALICE, LedgerMember.ROLE_OWNER);
        Category cat = category(saved.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");

        ApiException ex = catchThrowableOfType(() -> service().create(ALICE, saved.getId(),
                new BigDecimal("30.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 删除：未涉结算才可删，回滚账户与分摊（需求 9.2a、9.3）----------------

    @Test
    void delete_payerIsSelf_rollsBackAccountAndRemovesSplits() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");

        service().delete(ALICE, l.getId(), tx.getId());

        // 付款账户回滚到扣款前（需求 9.2a）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        // 分摊行清除、交易从常规查询中消失。
        assertThat(splitRepository.findByTransactionId(tx.getId())).isEmpty();
        assertThat(expenseVisible(l.getId(), tx.getId())).isFalse();
    }

    @Test
    void delete_payerIsOther_removesSplitsWithoutTouchingAccounts() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account aliceAcc = account(ALICE, "现金", "500.00");
        // Alice 记账，付款人 Bob（不动 Alice 账户，也不记付款账户）。
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("60.00"), cat.getId(),
                BOB, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        service().delete(ALICE, l.getId(), tx.getId());

        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("500.00");
        assertThat(splitRepository.findByTransactionId(tx.getId())).isEmpty();
        assertThat(expenseVisible(l.getId(), tx.getId())).isFalse();
    }

    @Test
    void delete_ownerCanDeleteOtherMemberExpense() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account bobAcc = account(BOB, "现金", "200.00");
        // Bob 记账并付款，Alice(owner) 删除。
        Transaction tx = service().create(BOB, l.getId(), new BigDecimal("30.00"), cat.getId(),
                BOB, bobAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        assertThat(balanceOf(bobAcc.getId())).isEqualByComparingTo("170.00");

        service().delete(ALICE, l.getId(), tx.getId());

        // 回滚 Bob 的付款账户（owner 有权删他人记的笔，需求 9.2）。
        assertThat(balanceOf(bobAcc.getId())).isEqualByComparingTo("200.00");
        assertThat(expenseVisible(l.getId(), tx.getId())).isFalse();
    }

    @Test
    void delete_byNonOwnerNonCreator_rejectedForbidden() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account aliceAcc = account(ALICE, "现金", "300.00");
        // Alice(owner) 记账付款；Carol 既非 owner 亦非记账人 → 无权删。
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(CAROL, l.getId(), tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("LEDGER_FORBIDDEN");
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("210.00");
        assertThat(splitRepository.findByTransactionId(tx.getId())).hasSize(3);
    }

    @Test
    void delete_whenSettlementExists_rejectedWithExpenseSettled() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 账本内存在一条未撤销结算 → 拒删（需求 9.2b）。
        settlement(l.getId(), BOB, ALICE, "30.00");

        ApiException ex = catchThrowableOfType(
                () -> service().delete(ALICE, l.getId(), tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_EXPENSE_SETTLED");
        // 零副作用：账户与分摊不变。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        assertThat(splitRepository.findByTransactionId(tx.getId())).hasSize(3);
        assertThat(expenseVisible(l.getId(), tx.getId())).isTrue();
    }

    @Test
    void delete_whenSettlementReverted_allowed() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 已撤销的结算不阻止删除（净额计算忽略已撤销行）。
        AaSettlement s = settlement(l.getId(), BOB, ALICE, "30.00");
        s.setRevertedAt(LocalDateTime.ofInstant(T0, ZONE));
        settlementRepository.save(s);

        service().delete(ALICE, l.getId(), tx.getId());

        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        assertThat(expenseVisible(l.getId(), tx.getId())).isFalse();
    }

    @Test
    void delete_archivedLedger_rejected() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 归档账本只读。
        l.setArchivedAt(LocalDateTime.ofInstant(T0, ZONE));
        ledgerRepository.save(l);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(ALICE, l.getId(), tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        assertThat(expenseVisible(l.getId(), tx.getId())).isTrue();
    }

    @Test
    void delete_nonMemberCurrentUser_returnsNotFound() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(OUTSIDER, l.getId(), tx.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
        assertThat(expenseVisible(l.getId(), tx.getId())).isTrue();
    }

    @Test
    void delete_unknownExpense_returnsNotFound() {
        Ledger l = threeMemberLedger(false);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(ALICE, l.getId(), 99999L), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 编辑：回滚旧效果 + 按新参数重建（需求 9.2a、9.3）----------------

    @Test
    void update_changesAmountAndSplits_recomputesAccountAndShares() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "旧", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");

        // 改为 60（Alice 付），依旧本人付款 → 回滚旧 90、扣新 60。
        Transaction updated = service().update(ALICE, l.getId(), tx.getId(), new BigDecimal("60.00"),
                cat.getId(), ALICE, acc.getId(), null, "新", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        assertThat(updated.getId()).isEqualTo(tx.getId());
        assertThat(updated.getAmount()).isEqualByComparingTo("60.00");
        assertThat(updated.getNote()).isEqualTo("新");
        // 账户：300 −90 +90 −60 = 240（无漂移）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("240.00");
        Map<Long, BigDecimal> shares = sharesOf(tx.getId());
        assertThat(shares).hasSize(3);
        assertThat(shares.get(ALICE)).isEqualByComparingTo("20.00");
        assertThat(shares.get(BOB)).isEqualByComparingTo("20.00");
        assertThat(shares.get(CAROL)).isEqualByComparingTo("20.00");
    }

    @Test
    void update_changePayerFromSelfToOther_refundsOldAccount() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");

        // 付款人改为 Bob → 回滚 Alice 账户，不记付款账户、不动本人账户。
        Transaction updated = service().update(ALICE, l.getId(), tx.getId(), new BigDecimal("90.00"),
                cat.getId(), BOB, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        assertThat(updated.getPayerUserId()).isEqualTo(BOB);
        assertThat(updated.getAccountId()).isNull();
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
    }

    @Test
    void update_customSplitMismatch_rejected() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, l.getId(), tx.getId(),
                new BigDecimal("100.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_CUSTOM, List.of(ALICE, BOB, CAROL),
                Map.of(ALICE, new BigDecimal("50.00"), BOB, new BigDecimal("30.00"),
                        CAROL, new BigDecimal("10.00"))), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_SPLIT_MISMATCH");
    }

    @Test
    void update_whenSettlementExists_rejectedWithExpenseSettled() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        settlement(l.getId(), BOB, ALICE, "30.00");

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, l.getId(), tx.getId(),
                new BigDecimal("60.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB, CAROL), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_EXPENSE_SETTLED");
        // 零副作用：金额/账户/分摊保持原值。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        assertThat(transactionRepository.findByIdAndLedgerId(tx.getId(), l.getId()).orElseThrow()
                .getAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    void update_archivedLedger_rejected() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId(), "餐饮");
        Account acc = account(ALICE, "现金", "300.00");
        Transaction tx = service().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        l.setArchivedAt(LocalDateTime.ofInstant(T0, ZONE));
        ledgerRepository.save(l);

        ApiException ex = catchThrowableOfType(() -> service().update(ALICE, l.getId(), tx.getId(),
                new BigDecimal("60.00"), cat.getId(), ALICE, acc.getId(), null, null,
                AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB, CAROL), null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
    }
}
