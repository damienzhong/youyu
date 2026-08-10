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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.AaSettlementResponse;
import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * {@link AaSettlementService#settlement} 的示例与边界单元测试（H2 + 真实 Repository，任务 4.1）。
 *
 * <p>验证净额派生（应收正 / 应付负、Σnet=0）、最小化清算建议（笔数 ≤ n−1、金额之和 = 总应付）、
 * 结算与撤销对净额的影响、软删除支出被排除、只读账本仍可查看、越权 NOT_FOUND
 * （需求 5.1、5.2、5.3、5.4、5.5、8.1、9.4）。计算下沉 {@link AaMath}，本测试聚焦服务装配与数据来源正确性。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AaSettlementServiceTest {

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

    private AaSettlementService service() {
        Clock clock = Clock.fixed(T0, ZONE);
        return new AaSettlementService(transactionRepository, splitRepository, settlementRepository,
                ledgerRepository, memberRepository, accountRepository, clock);
    }

    /** 记账服务（复用真实 create，产出 aa_expense + splits + 账户扣款）。 */
    private AaExpenseService expenseService() {
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

    private Ledger threeMemberLedger(boolean archived) {
        Ledger l = aaLedger(ALICE, archived);
        member(l.getId(), ALICE, LedgerMember.ROLE_OWNER);
        member(l.getId(), BOB, LedgerMember.ROLE_EDITOR);
        member(l.getId(), CAROL, LedgerMember.ROLE_EDITOR);
        return l;
    }

    private Account account(long userId, String balance) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private Category category(long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private AaSettlement settlement(long ledgerId, long from, long to, String amount, boolean reverted) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        AaSettlement s = new AaSettlement();
        s.setLedgerId(ledgerId);
        s.setFromUserId(from);
        s.setToUserId(to);
        s.setAmount(new BigDecimal(amount));
        s.setSettledBy(from);
        s.setSettledAt(now);
        if (reverted) {
            s.setRevertedAt(now);
        }
        return settlementRepository.save(s);
    }

    private Map<Long, BigDecimal> netByUser(AaSettlementResponse resp) {
        return resp.nets().stream()
                .collect(Collectors.toMap(AaSettlementResponse.MemberNet::userId,
                        AaSettlementResponse.MemberNet::net));
    }

    // ---------------- 净额与清算建议 ----------------

    @Test
    void settlement_singleExpense_payerHasReceivable_othersPayable() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        // Alice 付 90，三人均分（各 30）。Alice 借出 60（应收），Bob/Carol 各欠 30（应付）。
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        AaSettlementResponse resp = service().settlement(ALICE, l.getId());

        assertThat(resp.ledgerId()).isEqualTo(l.getId());
        assertThat(resp.allSettled()).isFalse();
        Map<Long, BigDecimal> net = netByUser(resp);
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        // Σnet = 0（Property 2 / 需求 5.1）。
        assertThat(net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");

        // 建议转账：Bob→Alice 30、Carol→Alice 30（笔数 2 ≤ n−1=2，金额之和 = 总应付 60）。
        assertThat(resp.suggestedTransfers()).hasSize(2);
        BigDecimal transferSum = resp.suggestedTransfers().stream()
                .map(AaSettlementResponse.SuggestedTransfer::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(transferSum).isEqualByComparingTo("60.00");
        assertThat(resp.suggestedTransfers())
                .allSatisfy(t -> assertThat(t.toUserId()).isEqualTo(ALICE));
        assertThat(resp.suggestedTransfers().stream()
                .map(AaSettlementResponse.SuggestedTransfer::fromUserId).toList())
                .containsExactlyInAnyOrder(BOB, CAROL);
    }

    @Test
    void settlement_afterSettlementRecorded_netsReduce() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // Bob 已结清对 Alice 的 30：Bob net 归 0，Alice 应收降到 30。
        settlement(l.getId(), BOB, ALICE, "30.00", false);

        Map<Long, BigDecimal> net = netByUser(service().settlement(ALICE, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("30.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("0.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
    }

    @Test
    void settlement_revertedSettlementIgnored() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 已撤销的结算不参与净额计算（需求 9.3）。
        settlement(l.getId(), BOB, ALICE, "30.00", true);

        Map<Long, BigDecimal> net = netByUser(service().settlement(ALICE, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
    }

    @Test
    void settlement_fullySettled_allZero_noTransfers() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        settlement(l.getId(), BOB, ALICE, "30.00", false);
        settlement(l.getId(), CAROL, ALICE, "30.00", false);

        AaSettlementResponse resp = service().settlement(ALICE, l.getId());
        assertThat(resp.allSettled()).isTrue();
        assertThat(resp.suggestedTransfers()).isEmpty();
        assertThat(netByUser(resp).values())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
    }

    @Test
    void settlement_noExpenses_allZero() {
        Ledger l = threeMemberLedger(false);

        AaSettlementResponse resp = service().settlement(ALICE, l.getId());
        assertThat(resp.nets()).hasSize(3);
        assertThat(resp.allSettled()).isTrue();
        assertThat(resp.suggestedTransfers()).isEmpty();
    }

    @Test
    void settlement_transferCountAtMostNMinusOne() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "500.00");
        Account bobAcc = account(BOB, "500.00");
        // 两笔不同付款人，形成多向债务；建议转账笔数 ≤ 3−1 = 2（需求 5.3）。
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        expenseService().create(BOB, l.getId(), new BigDecimal("30.00"), cat.getId(),
                BOB, bobAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        AaSettlementResponse resp = service().settlement(ALICE, l.getId());
        assertThat(resp.suggestedTransfers().size()).isLessThanOrEqualTo(2);
        assertThat(netByUser(resp).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");
    }

    // ---------------- 软删除支出被排除 ----------------

    @Test
    void settlement_softDeletedExpenseExcluded() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        var tx = expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 删除该笔后净额应全部归零（回滚重算，需求 9.3）。
        expenseService().delete(ALICE, l.getId(), tx.getId());

        AaSettlementResponse resp = service().settlement(ALICE, l.getId());
        assertThat(resp.allSettled()).isTrue();
        assertThat(resp.suggestedTransfers()).isEmpty();
    }

    // ---------------- 只读账本仍可查看 / 越权 NOT_FOUND ----------------

    @Test
    void settlement_archivedLedger_stillReadable() {
        Ledger l = threeMemberLedger(true);
        // 归档账本只读，但结算视图仍可查看（需求 8.3）。
        AaSettlementResponse resp = service().settlement(ALICE, l.getId());
        assertThat(resp.nets()).hasSize(3);
    }

    @Test
    void settlement_nonMember_returnsNotFound() {
        Ledger l = threeMemberLedger(false);

        ApiException ex = catchThrowableOfType(
                () -> service().settlement(OUTSIDER, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void settlement_nonAaLedger_returnsNotFound() {
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

        ApiException ex = catchThrowableOfType(
                () -> service().settlement(ALICE, saved.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 结清一条（settle，任务 4.2 / 需求 6.1-6.4、6.6） ----------------

    @Test
    void settle_asReceiver_creditsAccount_andReducesReceivable() {
        Ledger l = threeMemberLedger(false);
        Category realCat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), realCat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // 付 90 后 Alice 账户 300 → 210。
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("210.00");

        // Alice 作为收款方结清 Bob 的 30：本人账户 +30、应收 −30。
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());

        assertThat(s.getFromUserId()).isEqualTo(BOB);
        assertThat(s.getToUserId()).isEqualTo(ALICE);
        assertThat(s.getToAccountId()).isEqualTo(aliceAcc.getId());
        assertThat(s.getFromAccountId()).isNull();
        assertThat(s.getSettledBy()).isEqualTo(ALICE);
        // 账户 +30 → 240。
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("240.00");
        // 净额：Alice 应收降到 30，Bob 归 0，Carol 仍 −30。
        Map<Long, BigDecimal> net = netByUser(service().settlement(ALICE, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("30.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("0.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        // 生成一条 aa_settlement 展示流水（记在本人账户上）。
        assertThat(transactionRepository.findByLedgerId(l.getId()).stream()
                .anyMatch(t -> t.getType() == com.damien.youyu.domain.TransactionType.AA_SETTLEMENT
                        && aliceAcc.getId().equals(t.getAccountId()))).isTrue();
    }

    @Test
    void settle_asPayer_debitsAccount_andReducesPayable() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        Account bobAcc = account(BOB, "100.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        // Bob 作为付款方结清对 Alice 的 30：本人账户 −30、应付 −30。
        AaSettlement s = service().settle(BOB, l.getId(), ALICE, null,
                new BigDecimal("30.00"), bobAcc.getId());

        assertThat(s.getFromUserId()).isEqualTo(BOB);
        assertThat(s.getToUserId()).isEqualTo(ALICE);
        assertThat(s.getFromAccountId()).isEqualTo(bobAcc.getId());
        assertThat(s.getToAccountId()).isNull();
        // Bob 账户 100 → 70。
        assertThat(accountRepository.findById(bobAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("70.00");
        Map<Long, BigDecimal> net = netByUser(service().settlement(BOB, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("30.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("0.00");
    }

    @Test
    void settle_amountExceedsOwed_returnsInvalid_andNoSideEffect() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        BigDecimal before = accountRepository.findById(aliceAcc.getId()).orElseThrow()
                .getCurrentBalance();

        // Bob 只欠 30，收 40 超额 → AA_SETTLEMENT_INVALID，零副作用。
        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), null, BOB,
                        new BigDecimal("40.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo(before);
        assertThat(settlementRepository.findByLedgerId(l.getId())).isEmpty();
    }

    @Test
    void settle_wrongDirection_creditorClaimsToPay_returnsInvalid() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        // Alice 是债权人（应收 60），却声称向 Bob 付款 → 方向与净额不符。
        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), BOB, null,
                        new BigDecimal("30.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
    }

    @Test
    void settle_bothCounterpartiesProvided_returnsInvalid() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), BOB, CAROL,
                        new BigDecimal("30.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
    }

    @Test
    void settle_counterpartyNotMember_returnsInvalid() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), null, OUTSIDER,
                        new BigDecimal("30.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
    }

    @Test
    void settle_nonPositiveAmount_returnsInvalid() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), null, BOB,
                        new BigDecimal("0.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
    }

    @Test
    void settle_archivedLedger_returnsArchived() {
        Ledger l = threeMemberLedger(true); // 归档只读
        Account aliceAcc = account(ALICE, "300.00");

        ApiException ex = catchThrowableOfType(
                () -> service().settle(ALICE, l.getId(), null, BOB,
                        new BigDecimal("30.00"), aliceAcc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");
    }

    @Test
    void settle_nonMember_returnsNotFound() {
        Ledger l = threeMemberLedger(false);
        Account acc = account(OUTSIDER, "300.00");

        ApiException ex = catchThrowableOfType(
                () -> service().settle(OUTSIDER, l.getId(), null, BOB,
                        new BigDecimal("30.00"), acc.getId()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 撤销结算（revert，任务 4.3 / 需求 6.5） ----------------

    @Test
    void revert_asReceiver_rollsBackAccount_andRestoresDebt() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // Alice 作为收款方结清 Bob 的 30：账户 210 → 240，应收 60 → 30。
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("240.00");

        // 撤销：账户回滚 240 → 210，reverted_at 落值。
        AaSettlement reverted = service().revert(ALICE, l.getId(), s.getId());

        assertThat(reverted.getRevertedAt()).isNotNull();
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("210.00");
        // 债务恢复：净额回到撤销前状态（Alice 60、Bob −30、Carol −30）。
        Map<Long, BigDecimal> net = netByUser(service().settlement(ALICE, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        // 展示流水被作废（软删除，不再出现在常规查询中）。
        assertThat(transactionRepository.findByLedgerId(l.getId()).stream()
                .noneMatch(t -> t.getType() == com.damien.youyu.domain.TransactionType.AA_SETTLEMENT))
                .isTrue();
    }

    @Test
    void revert_asPayer_rollsBackAccount_andRestoresDebt() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        Account bobAcc = account(BOB, "100.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // Bob 作为付款方结清对 Alice 的 30：Bob 账户 100 → 70。
        AaSettlement s = service().settle(BOB, l.getId(), ALICE, null,
                new BigDecimal("30.00"), bobAcc.getId());
        assertThat(accountRepository.findById(bobAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("70.00");

        // Bob 撤销：账户回滚 70 → 100，债务恢复。
        service().revert(BOB, l.getId(), s.getId());

        assertThat(accountRepository.findById(bobAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("100.00");
        Map<Long, BigDecimal> net = netByUser(service().settlement(BOB, l.getId()));
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
    }

    @Test
    void revert_alreadyReverted_returnsInvalid() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());
        service().revert(ALICE, l.getId(), s.getId());
        BigDecimal balanceAfterFirstRevert = accountRepository.findById(aliceAcc.getId())
                .orElseThrow().getCurrentBalance();

        // 再次撤销 → AA_SETTLEMENT_INVALID，零副作用（账户不再变动）。
        ApiException ex = catchThrowableOfType(
                () -> service().revert(ALICE, l.getId(), s.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_SETTLEMENT_INVALID");
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo(balanceAfterFirstRevert);
    }

    @Test
    void revert_byNonSettlerCounterparty_returnsForbidden() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // Alice 结清（收款方）：只有 Alice 侧账户被动过，只有 Alice 可撤销。
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());

        // Bob 是另一方当事人，但其账户未被动过 → 无权撤销。
        ApiException ex = catchThrowableOfType(
                () -> service().revert(BOB, l.getId(), s.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_FORBIDDEN");
        // 账户与结算状态不变。
        assertThat(accountRepository.findById(aliceAcc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("240.00");
        assertThat(settlementRepository.findById(s.getId()).orElseThrow().getRevertedAt()).isNull();
    }

    @Test
    void revert_archivedLedger_returnsArchived() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());
        // 归档后只读。
        l.setArchivedAt(LocalDateTime.ofInstant(T0, ZONE));
        ledgerRepository.save(l);

        ApiException ex = catchThrowableOfType(
                () -> service().revert(ALICE, l.getId(), s.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");
    }

    @Test
    void revert_nonMember_returnsNotFound() {
        Ledger l = threeMemberLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        AaSettlement s = service().settle(ALICE, l.getId(), null, BOB,
                new BigDecimal("30.00"), aliceAcc.getId());

        ApiException ex = catchThrowableOfType(
                () -> service().revert(OUTSIDER, l.getId(), s.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void revert_settlementNotInLedger_returnsNotFound() {
        Ledger l = threeMemberLedger(false);

        ApiException ex = catchThrowableOfType(
                () -> service().revert(ALICE, l.getId(), 999999L), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }
}
