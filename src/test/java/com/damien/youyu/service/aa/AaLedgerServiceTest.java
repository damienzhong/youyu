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

import com.damien.youyu.api.dto.AaOverviewResponse;
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
 * {@link AaLedgerService#overview} 的示例与边界单元测试（H2 + 真实 Repository，任务 5.1）。
 *
 * <p>验证首页 hero 三口径（账户已支出 / 我的消费 / 待收回，需求 4.4、7.1、7.2）、成员净额（应收正 /
 * 应付负、Σ=0，需求 5.1）、合并流水（AA 支出 + 未撤销结算，标注付款人 / 我摊 / 收付成员）、只读账本仍可
 * 查看（需求 8.3）、越权 NOT_FOUND（需求 9.4）。净额与三口径口径与 {@link AaSettlementService} 一致。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AaLedgerServiceTest {

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

    private final Clock clock = Clock.fixed(T0, ZONE);

    private AaSettlementService settlementService() {
        return new AaSettlementService(transactionRepository, splitRepository, settlementRepository,
                ledgerRepository, memberRepository, accountRepository, clock);
    }

    private AaLedgerService service() {
        return new AaLedgerService(ledgerRepository, memberRepository, transactionRepository,
                splitRepository, settlementRepository, settlementService());
    }

    private AaExpenseService expenseService() {
        return new AaExpenseService(transactionRepository, splitRepository, accountRepository,
                categoryRepository, ledgerRepository, memberRepository, settlementRepository, clock);
    }

    // ---------------- fixtures ----------------

    private Ledger aaLedger(boolean archived) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ALICE);
        l.setName("旅行 AA");
        l.setType(Ledger.TYPE_AA);
        l.setSortOrder(0);
        l.setDefault(false);
        if (archived) {
            l.setArchivedAt(now);
        }
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(l);
        member(saved.getId(), ALICE, LedgerMember.ROLE_OWNER);
        member(saved.getId(), BOB, LedgerMember.ROLE_EDITOR);
        member(saved.getId(), CAROL, LedgerMember.ROLE_EDITOR);
        return saved;
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

    private Map<Long, BigDecimal> netByUser(AaOverviewResponse resp) {
        return resp.memberNets().stream()
                .collect(Collectors.toMap(AaOverviewResponse.MemberNet::userId,
                        AaOverviewResponse.MemberNet::net));
    }

    // ---------------- 三口径 + 成员净额 ----------------

    @Test
    void overview_selfPaid_calibersAndNets() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        // Alice 付 90，三人均分（各 30）。Alice 账户 300→210。
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        AaOverviewResponse resp = service().overview(ALICE, l.getId());

        assertThat(resp.ledgerId()).isEqualTo(l.getId());
        assertThat(resp.archived()).isFalse();
        assertThat(resp.allSettled()).isFalse();
        // 账户已支出 = 实付 90（真实现金流出，等于账户 300→210 的下降）。
        assertThat(resp.calibers().accountPaid()).isEqualByComparingTo("90.00");
        // 我的消费 = 自身份额 30（应收/借出 60 不计消费）。
        assertThat(resp.calibers().myConsumption()).isEqualByComparingTo("30.00");
        // 待收回 = net = 60（别人还欠我的）。
        assertThat(resp.calibers().receivable()).isEqualByComparingTo("60.00");

        Map<Long, BigDecimal> net = netByUser(resp);
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        assertThat(net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void overview_payerIsOther_noAccountPaid_forCurrentUser() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account aliceAcc = account(ALICE, "300.00");
        // Alice 付款，均分。Bob 视角：未付款 → 账户已支出 0，消费 30，待收回 0（Bob 应付 30）。
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);

        AaOverviewResponse resp = service().overview(BOB, l.getId());

        assertThat(resp.calibers().accountPaid()).isEqualByComparingTo("0.00");
        assertThat(resp.calibers().myConsumption()).isEqualByComparingTo("30.00");
        // Bob 应付 30（net=-30）→ 待收回 0。
        assertThat(resp.calibers().receivable()).isEqualByComparingTo("0.00");
        assertThat(netByUser(resp).get(BOB)).isEqualByComparingTo("-30.00");
    }

    @Test
    void overview_afterSettlement_accountPaidNetsInFlow_receivableReduced() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        // Alice 作为收款方结清 Bob 的 30：账户 210→240（现金流入），应收 60→30。
        settlementService().settle(ALICE, l.getId(), null, BOB, new BigDecimal("30.00"), acc.getId());

        AaOverviewResponse resp = service().overview(ALICE, l.getId());

        // 账户已支出 = 90（付款流出） − 30（结算收到） = 60，等于账户 300→240 的净下降。
        assertThat(resp.calibers().accountPaid()).isEqualByComparingTo("60.00");
        // 消费不受结算影响，仍 30。
        assertThat(resp.calibers().myConsumption()).isEqualByComparingTo("30.00");
        // 待收回降到 30。
        assertThat(resp.calibers().receivable()).isEqualByComparingTo("30.00");
    }

    @Test
    void overview_transactions_containExpenseAndSettlement_annotated() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐", AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        settlementService().settle(ALICE, l.getId(), null, BOB, new BigDecimal("30.00"), acc.getId());

        List<AaOverviewResponse.TransactionItem> items = service().overview(ALICE, l.getId()).transactions();

        assertThat(items).hasSize(2);
        AaOverviewResponse.TransactionItem expense = items.stream()
                .filter(t -> t.type().equals("aa_expense")).findFirst().orElseThrow();
        assertThat(expense.payerUserId()).isEqualTo(ALICE);
        assertThat(expense.myShare()).isEqualByComparingTo("30.00");
        assertThat(expense.amount()).isEqualByComparingTo("90.00");
        assertThat(expense.fromUserId()).isNull();

        AaOverviewResponse.TransactionItem settle = items.stream()
                .filter(t -> t.type().equals("aa_settlement")).findFirst().orElseThrow();
        assertThat(settle.fromUserId()).isEqualTo(BOB);
        assertThat(settle.toUserId()).isEqualTo(ALICE);
        assertThat(settle.amount()).isEqualByComparingTo("30.00");
        assertThat(settle.payerUserId()).isNull();
        assertThat(settle.myShare()).isNull();
    }

    @Test
    void overview_nonParticipant_myShareNull() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        // 仅 Alice、Bob 参与分摊，Carol 不参与。
        expenseService().create(ALICE, l.getId(), new BigDecimal("80.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB), null);

        AaOverviewResponse resp = service().overview(CAROL, l.getId());
        AaOverviewResponse.TransactionItem expense = resp.transactions().get(0);
        // Carol 非参与人 → 我摊为 null，消费 0。
        assertThat(expense.myShare()).isNull();
        assertThat(resp.calibers().myConsumption()).isEqualByComparingTo("0.00");
    }

    @Test
    void overview_revertedSettlementExcludedFromFlow() {
        Ledger l = aaLedger(false);
        Category cat = category(l.getId());
        Account acc = account(ALICE, "300.00");
        expenseService().create(ALICE, l.getId(), new BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, null, AaExpenseService.SPLIT_EVEN,
                List.of(ALICE, BOB, CAROL), null);
        var s = settlementService().settle(ALICE, l.getId(), null, BOB, new BigDecimal("30.00"), acc.getId());
        settlementService().revert(ALICE, l.getId(), s.getId());

        AaOverviewResponse resp = service().overview(ALICE, l.getId());
        // 撤销后结算不出现在流水，且账户已支出恢复为 90、待收回恢复为 60。
        assertThat(resp.transactions()).noneMatch(t -> t.type().equals("aa_settlement"));
        assertThat(resp.calibers().accountPaid()).isEqualByComparingTo("90.00");
        assertThat(resp.calibers().receivable()).isEqualByComparingTo("60.00");
    }

    @Test
    void overview_noActivity_allZero() {
        Ledger l = aaLedger(false);

        AaOverviewResponse resp = service().overview(ALICE, l.getId());
        assertThat(resp.allSettled()).isTrue();
        assertThat(resp.memberNets()).hasSize(3);
        assertThat(resp.transactions()).isEmpty();
        assertThat(resp.calibers().accountPaid()).isEqualByComparingTo("0.00");
        assertThat(resp.calibers().myConsumption()).isEqualByComparingTo("0.00");
        assertThat(resp.calibers().receivable()).isEqualByComparingTo("0.00");
    }

    // ---------------- 归档仍可查看 / 越权 NOT_FOUND ----------------

    @Test
    void overview_archivedLedger_stillReadable() {
        Ledger l = aaLedger(true);
        AaOverviewResponse resp = service().overview(ALICE, l.getId());
        assertThat(resp.archived()).isTrue();
        assertThat(resp.memberNets()).hasSize(3);
    }

    @Test
    void overview_nonMember_returnsNotFound() {
        Ledger l = aaLedger(false);
        ApiException ex = catchThrowableOfType(
                () -> service().overview(OUTSIDER, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void overview_nonAaLedger_returnsNotFound() {
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
                () -> service().overview(ALICE, saved.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }
}
