package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerInvite;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.TransactionTemplateRepository;

/**
 * {@link LedgerService} 协作账本行为的示例/边界测试：成员制授权、邀请/加入、成员管理、
 * 新账本默认分类种子、删除级联（协作删账户 / 独立留账户）。H2 + 真实 Repository，固定 Clock。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LedgerServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long CAROL = 3L;

    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private CategoryBudgetRepository categoryBudgetRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private com.damien.youyu.repository.LoanRepaymentRepository loanRepaymentRepository;
    @Autowired private LedgerMemberRepository memberRepository;
    @Autowired private LedgerInviteRepository inviteRepository;
    @Autowired private TransactionTemplateRepository templateRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private TransactionTagRepository transactionTagRepository;
    @Autowired private com.damien.youyu.repository.TransactionSplitRepository splitRepository;
    @Autowired private com.damien.youyu.repository.AaSettlementRepository settlementRepository;

    private LedgerService service() {
        return serviceAt(T0);
    }

    private LedgerService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZONE);
        AccountService accountService =
                new AccountService(accountRepository, accountLedgerRepository, transactionRepository,
                        loanRepository, loanRepaymentRepository, clock);
        com.damien.youyu.service.aa.AaSettlementService aaSettlementService =
                new com.damien.youyu.service.aa.AaSettlementService(transactionRepository, splitRepository,
                        settlementRepository, ledgerRepository, memberRepository, accountRepository, clock);
        return new LedgerService(ledgerRepository, categoryRepository, accountRepository,
                accountLedgerRepository, transactionRepository, budgetRepository, categoryBudgetRepository,
                loanRepository, loanRepaymentRepository, memberRepository, inviteRepository, templateRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository, accountService,
                new InviteCodeGenerator(), aaSettlementService, clock);
    }

    /** AA 记账服务（用于构造非零净额场景）。 */
    private com.damien.youyu.service.aa.AaExpenseService expenseServiceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZONE);
        return new com.damien.youyu.service.aa.AaExpenseService(transactionRepository, splitRepository,
                accountRepository, categoryRepository, ledgerRepository, memberRepository,
                settlementRepository, clock);
    }

    // ---------------- 创建 / 默认分类 / 成员 ----------------

    @Test
    void create_seedsDefaultCategoriesAndOwnerMembership() {
        Ledger l = service().create(ALICE, "旅行", "INDEPENDENT");

        int expected = DefaultCategories.totalCount(DefaultCategories.EXPENSE)
                + DefaultCategories.totalCount(DefaultCategories.INCOME);
        assertThat(categoryRepository.countByLedgerId(l.getId())).isEqualTo(expected);
        LedgerMember m = memberRepository.findByLedgerIdAndUserId(l.getId(), ALICE).orElseThrow();
        assertThat(m.isOwner()).isTrue();
    }

    @Test
    void create_aaLedger_setsTypeAa_ownerMembership_andSeedsCategories() {
        // 需求 1.1：新建 type=AA 账本，归属创建者为 owner 并加入成员列表。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");

        assertThat(l.getType()).isEqualTo(Ledger.TYPE_AA);
        assertThat(l.isAa()).isTrue();
        // 未归档（archived_at 为空）。
        assertThat(l.getArchivedAt()).isNull();
        // 创建者为 OWNER 成员。
        LedgerMember m = memberRepository.findByLedgerIdAndUserId(l.getId(), ALICE).orElseThrow();
        assertThat(m.isOwner()).isTrue();
        // AA 账本记账仍需分类，故同样预置默认分类。
        int expected = DefaultCategories.totalCount(DefaultCategories.EXPENSE)
                + DefaultCategories.totalCount(DefaultCategories.INCOME);
        assertThat(categoryRepository.countByLedgerId(l.getId())).isEqualTo(expected);
    }

    @Test
    void create_typeCaseInsensitive_aa() {
        Ledger l = service().create(ALICE, "聚餐 AA", "aa");
        assertThat(l.getType()).isEqualTo(Ledger.TYPE_AA);
    }

    @Test
    void ensureDefaultLedger_doesNotSeedCategories() {
        Ledger def = service().ensureDefaultLedger(ALICE);
        assertThat(categoryRepository.countByLedgerId(def.getId())).isZero();
        // 但仍建立 OWNER 成员。
        assertThat(memberRepository.existsByLedgerIdAndUserId(def.getId(), ALICE)).isTrue();
    }

    // ---------------- 授权 ----------------

    @Test
    void requireAccessible_nonMember_ledgerNotAccessible() {
        Ledger l = service().create(ALICE, "私账", "PERSONAL");
        ApiException ex = catchThrowableOfType(
                () -> service().requireAccessible(BOB, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_NOT_ACCESSIBLE");
    }

    @Test
    void requireOwner_editorForbidden_ownerOk() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        LedgerInvite inv = service().createInvite(ALICE, l.getId());
        service().join(BOB, inv.getCode());

        // owner ok
        assertThat(service().requireOwner(ALICE, l.getId()).getId()).isEqualTo(l.getId());
        // editor forbidden
        ApiException ex = catchThrowableOfType(
                () -> service().requireOwner(BOB, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_FORBIDDEN");
    }

    // ---------------- 邀请 / 加入 ----------------

    @Test
    void invite_onIndependentLedger_rejected() {
        Ledger l = service().create(ALICE, "私账", "INDEPENDENT");
        ApiException ex = catchThrowableOfType(
                () -> service().createInvite(ALICE, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_NOT_COLLABORATIVE");
    }

    @Test
    void invite_onAaLedger_allowed() {
        // 需求 2.1：AA 账本复用既有邀请机制，可生成邀请码。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        LedgerInvite inv = service().createInvite(ALICE, l.getId());
        assertThat(inv.getCode()).isNotBlank();
        assertThat(inv.getLedgerId()).isEqualTo(l.getId());
    }

    @Test
    void invite_onPersonalLedger_rejected() {
        // 个人账本无成员语义，仍拒绝邀请（只有协作 / AA 可邀请）。
        Ledger l = service().create(ALICE, "私账", "PERSONAL");
        ApiException ex = catchThrowableOfType(
                () -> service().createInvite(ALICE, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_NOT_COLLABORATIVE");
    }

    @Test
    void join_aaLedger_addsEditorMembership_andIsIdempotent() {
        // 需求 2.3：受邀人加入 AA 账本登记为成员（EDITOR，对应注册 user_id）。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        LedgerInvite inv = service().createInvite(ALICE, l.getId());

        service().join(BOB, inv.getCode());
        service().join(BOB, inv.getCode()); // 幂等

        assertThat(memberRepository.countByLedgerId(l.getId())).isEqualTo(2);
        assertThat(memberRepository.findByLedgerIdAndUserId(l.getId(), BOB).orElseThrow().getRole())
                .isEqualTo(LedgerMember.ROLE_EDITOR);
        // 创建者仍为 OWNER。
        assertThat(memberRepository.findByLedgerIdAndUserId(l.getId(), ALICE).orElseThrow().isOwner())
                .isTrue();
    }

    @Test
    void join_addsEditorMembership_andIsIdempotent() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        LedgerInvite inv = service().createInvite(ALICE, l.getId());

        service().join(BOB, inv.getCode());
        service().join(BOB, inv.getCode()); // 幂等

        assertThat(memberRepository.countByLedgerId(l.getId())).isEqualTo(2);
        assertThat(memberRepository.findByLedgerIdAndUserId(l.getId(), BOB).orElseThrow().getRole())
                .isEqualTo(LedgerMember.ROLE_EDITOR);
    }

    @Test
    void join_invalidCode_rejected() {
        ApiException ex = catchThrowableOfType(
                () -> service().join(BOB, "NOPE1234"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("INVITE_INVALID");
    }

    @Test
    void join_expiredCode_rejected() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        LedgerInvite inv = service().createInvite(ALICE, l.getId());
        // 8 天后加入 → 已过期（TTL 7 天）。
        ApiException ex = catchThrowableOfType(
                () -> serviceAt(T0.plusSeconds(8 * 24 * 3600)).join(BOB, inv.getCode()),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("INVITE_INVALID");
    }

    // ---------------- 成员管理 ----------------

    @Test
    void members_listsOwnerAndEditors() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        List<LedgerMember> members = service().members(ALICE, l.getId());
        assertThat(members).extracting(LedgerMember::getUserId).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void removeMember_ownerRemovesEditor() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        service().removeMember(ALICE, l.getId(), BOB);
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isFalse();
    }

    @Test
    void removeMember_selfLeave() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        service().removeMember(BOB, l.getId(), BOB); // 自己退出
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isFalse();
    }

    @Test
    void removeMember_cannotRemoveOwner() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        ApiException ex = catchThrowableOfType(
                () -> service().removeMember(ALICE, l.getId(), ALICE), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("MEMBER_OWNER_IMMUTABLE");
    }

    @Test
    void editorCannotRemoveOtherEditor() {
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        String code = service().createInvite(ALICE, l.getId()).getCode();
        service().join(BOB, code);
        service().join(CAROL, code);

        // BOB(editor) 试图移除 CAROL(editor) → 需要 OWNER。
        ApiException ex = catchThrowableOfType(
                () -> service().removeMember(BOB, l.getId(), CAROL), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_FORBIDDEN");
    }

    // ---------------- AA 账本：退出 / 移除（净额 = 0 才可，需求 2.6-2.8） ----------------

    /** 在给定 AA 账本内建一个 EXPENSE 分类（AA 记账需要分类）。 */
    private com.damien.youyu.domain.Category aaCategory(long ledgerId) {
        com.damien.youyu.domain.Category c = new com.damien.youyu.domain.Category();
        c.setLedgerId(ledgerId);
        c.setKind(com.damien.youyu.domain.CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(java.time.LocalDateTime.ofInstant(T0, ZONE));
        c.setUpdatedAt(java.time.LocalDateTime.ofInstant(T0, ZONE));
        return categoryRepository.save(c);
    }

    /** 为某用户建一个现金账户（付款账户）。 */
    private com.damien.youyu.domain.Account aaAccount(long userId, String balance) {
        com.damien.youyu.domain.Account a = new com.damien.youyu.domain.Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(com.damien.youyu.domain.AccountType.CASH);
        a.setInitialBalance(new java.math.BigDecimal(balance));
        a.setCurrentBalance(new java.math.BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(java.time.LocalDateTime.ofInstant(T0, ZONE));
        a.setUpdatedAt(java.time.LocalDateTime.ofInstant(T0, ZONE));
        return accountRepository.save(a);
    }

    @Test
    void removeMember_aaLedger_nonZeroNet_blocked() {
        // 需求 2.6：AA 成员仍有未结清净额时，OWNER 移除被阻止（AA_MEMBER_UNSETTLED）。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var aliceAcc = aaAccount(ALICE, "300.00");
        // Alice 付 90，Alice+Bob 均分（各 45）→ Bob net=-45（应付）。
        expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);

        ApiException ex = catchThrowableOfType(
                () -> service().removeMember(ALICE, l.getId(), BOB), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_MEMBER_UNSETTLED");
        // 被阻止：仍是成员。
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isTrue();
    }

    @Test
    void removeMember_aaLedger_nonZeroNet_selfLeaveBlocked() {
        // 需求 2.6：AA 成员自行退出同样受净额约束（此处付款人 Alice 有应收，退出被阻止）。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var aliceAcc = aaAccount(ALICE, "300.00");
        // Alice 付 90，Alice+Bob 均分 → Bob 试图退出但 net=-45。
        expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, null,
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);

        ApiException ex = catchThrowableOfType(
                () -> service().removeMember(BOB, l.getId(), BOB), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_MEMBER_UNSETTLED");
    }

    @Test
    void removeMember_aaLedger_zeroNet_removedAndHistoryPreserved() {
        // 需求 2.6、2.7：净额为 0 的 AA 成员可被移除，且其历史流水与分摊记录保留。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var bobAcc = aaAccount(BOB, "100.00");
        // Bob 付 50 且仅 Bob 参与分摊 → Bob paid=consumed=50，net=0，但有历史（1 笔支出 + 1 条分摊）。
        var expense = expenseServiceAt(T0).create(BOB, l.getId(), new java.math.BigDecimal("50.00"),
                cat.getId(), BOB, bobAcc.getId(), null, "打车",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(BOB), null);
        // 前置确认 Bob 净额为 0。
        assertThat(new com.damien.youyu.service.aa.AaSettlementService(transactionRepository, splitRepository,
                settlementRepository, ledgerRepository, memberRepository, accountRepository,
                Clock.fixed(T0, ZONE)).netCentsByUser(l.getId()).getOrDefault(BOB, 0L)).isZero();

        service().removeMember(ALICE, l.getId(), BOB);

        // 已移出成员列表。
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isFalse();
        // 历史流水保留：aa_expense 交易仍在（未软删除）。
        assertThat(transactionRepository.findByIdAndLedgerId(expense.getId(), l.getId())).isPresent();
        // 分摊记录保留。
        assertThat(splitRepository.findByTransactionId(expense.getId())).isNotEmpty();
    }

    @Test
    void removeMember_aaLedger_noActivity_zeroNet_removable() {
        // 净额为 0（无任何活动）时，AA 成员可正常移除。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        service().removeMember(ALICE, l.getId(), BOB);
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isFalse();
    }

    @Test
    void removeMember_aaLedger_ownerStillImmutable() {
        // 需求 2.8：即便净额为 0，也不可移除 / 退出 AA 账本创建者。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        ApiException ex = catchThrowableOfType(
                () -> service().removeMember(ALICE, l.getId(), ALICE), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("MEMBER_OWNER_IMMUTABLE");
    }

    @Test
    void removeMember_collaborativeLedger_noNetCheck_evenIfActivityWouldExist() {
        // COLLABORATIVE 账本不做净额校验，移除行为与既有一致（需求：仅 AA 受 2.6 约束）。
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        service().removeMember(ALICE, l.getId(), BOB);
        assertThat(memberRepository.existsByLedgerIdAndUserId(l.getId(), BOB)).isFalse();
    }

    // ---------------- AA 账本：归档 / 解档（需求 8.3-8.5） ----------------

    @Test
    void archive_aaLedger_allSettled_noForceNeeded() {
        // 需求 8.3：全部结清的 AA 账本可直接归档（无需 force），置只读。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var bobAcc = aaAccount(BOB, "100.00");
        // Bob 付 50 且仅 Bob 参与 → net 全 0（已结清）。
        expenseServiceAt(T0).create(BOB, l.getId(), new java.math.BigDecimal("50.00"), cat.getId(),
                BOB, bobAcc.getId(), null, "打车",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(BOB), null);

        Ledger archived = service().archive(ALICE, l.getId(), false);

        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(ledgerRepository.findById(l.getId()).orElseThrow().isArchived()).isTrue();
    }

    @Test
    void archive_aaLedger_noActivity_noForceNeeded() {
        // 无任何活动（净额全 0）也可直接归档。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");

        Ledger archived = service().archive(ALICE, l.getId(), false);
        assertThat(archived.isArchived()).isTrue();
    }

    @Test
    void archive_aaLedger_unsettled_withoutForce_blocked() {
        // 需求 8.4：仍有未结清净额时，未带 force 归档被拒（AA_LEDGER_UNSETTLED）。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var aliceAcc = aaAccount(ALICE, "300.00");
        // Alice 付 90，Alice+Bob 均分 → Bob net=-45（未结清）。
        expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);

        ApiException ex = catchThrowableOfType(
                () -> service().archive(ALICE, l.getId(), false), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_UNSETTLED");
        // 未归档。
        assertThat(ledgerRepository.findById(l.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void archive_aaLedger_unsettled_withForce_archives() {
        // 需求 8.4：二次确认（force=true）后，未结清账本仍可归档。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var aliceAcc = aaAccount(ALICE, "300.00");
        expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("90.00"), cat.getId(),
                ALICE, aliceAcc.getId(), null, "聚餐",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);

        Ledger archived = service().archive(ALICE, l.getId(), true);
        assertThat(archived.isArchived()).isTrue();
    }

    @Test
    void archive_aaLedger_makesWritesRejected_thenUnarchiveRestores() {
        // 需求 8.3、8.5、9.5：归档后 AA 写操作被拒（AA_LEDGER_ARCHIVED）；解档后恢复可写。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());
        var cat = aaCategory(l.getId());
        var aliceAcc = aaAccount(ALICE, "300.00");

        service().archive(ALICE, l.getId(), false);

        // 归档后记一笔 AA 支出被拒。
        ApiException ex = catchThrowableOfType(
                () -> expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("60.00"),
                        cat.getId(), ALICE, aliceAcc.getId(), null, "聚餐",
                        com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN,
                        List.of(ALICE, BOB), null),
                ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_LEDGER_ARCHIVED");

        // 解档恢复可写。
        Ledger restored = service().unarchive(ALICE, l.getId());
        assertThat(restored.isArchived()).isFalse();
        var expense = expenseServiceAt(T0).create(ALICE, l.getId(), new java.math.BigDecimal("60.00"),
                cat.getId(), ALICE, aliceAcc.getId(), null, "聚餐",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);
        assertThat(expense.getId()).isNotNull();
    }

    @Test
    void archive_isIdempotent() {
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        Ledger first = service().archive(ALICE, l.getId(), false);
        Ledger second = service().archive(ALICE, l.getId(), false);
        assertThat(first.getArchivedAt()).isEqualTo(second.getArchivedAt());
    }

    @Test
    void unarchive_notArchived_isIdempotent() {
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        Ledger res = service().unarchive(ALICE, l.getId());
        assertThat(res.isArchived()).isFalse();
    }

    @Test
    void archive_nonOwnerEditor_forbidden() {
        // OWNER-only：EDITOR 归档被拒 LEDGER_FORBIDDEN。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        service().join(BOB, service().createInvite(ALICE, l.getId()).getCode());

        ApiException ex = catchThrowableOfType(
                () -> service().archive(BOB, l.getId(), false), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("LEDGER_FORBIDDEN");
    }

    @Test
    void archive_nonMember_notFound() {
        // 越权（非成员）→ NOT_FOUND，不泄漏存在性。
        Ledger l = service().create(ALICE, "旅行 AA", "AA");
        ApiException ex = catchThrowableOfType(
                () -> service().archive(CAROL, l.getId(), false), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void archive_nonAaLedger_rejected() {
        // 仅 AA 账本支持归档：协作账本归档被拒 AA_ARCHIVE_NOT_SUPPORTED。
        Ledger l = service().create(ALICE, "合租", "COLLABORATIVE");
        ApiException ex = catchThrowableOfType(
                () -> service().archive(ALICE, l.getId(), false), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_ARCHIVE_NOT_SUPPORTED");
    }

    @Test
    void unarchive_nonAaLedger_rejected() {
        Ledger l = service().create(ALICE, "私账", "PERSONAL");
        ApiException ex = catchThrowableOfType(
                () -> service().unarchive(ALICE, l.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("AA_ARCHIVE_NOT_SUPPORTED");
    }

    // ---------------- 列表包含已加入账本 ----------------

    @Test
    void list_includesJoinedCollaborativeLedgers() {
        Ledger shared = service().create(ALICE, "合租", "COLLABORATIVE");
        service().join(BOB, service().createInvite(ALICE, shared.getId()).getCode());

        List<Ledger> bobLedgers = service().list(BOB);
        // BOB 有自己的默认账本 + 已加入的协作账本。
        assertThat(bobLedgers).extracting(Ledger::getId).contains(shared.getId());
        assertThat(bobLedgers.stream().anyMatch(l -> l.getUserId().equals(BOB) && l.isDefault()))
                .isTrue();
    }

    // ---------------- 删除级联：协作删账户 / 独立留账户 ----------------

    @Test
    void delete_personalLedger_keepsAccounts() {
        service().ensureDefaultLedger(ALICE); // 保证 >1 个自有账本
        Ledger l = service().create(ALICE, "私账2", "PERSONAL");
        AccountService accSvc = new AccountService(accountRepository, accountLedgerRepository,
                transactionRepository, loanRepository, loanRepaymentRepository, Clock.fixed(T0, ZONE));
        // 账户纳入待删账本。
        var acc = accSvc.create(ALICE, "现金", "CASH", new java.math.BigDecimal("10.00"), 0,
                true, false, null, null, l.getId());

        service().delete(ALICE, l.getId());

        // 账户是独立实体，不随账本删除；其账本关联被清除。
        assertThat(accountRepository.findByIdAndUserId(acc.getId(), ALICE)).isPresent();
        assertThat(accountLedgerRepository.findByAccountIdAndLedgerId(acc.getId(), l.getId())).isEmpty();
    }

    @Test
    void delete_collaborativeLedger_keepsAccountsButRemovesLinks() {
        service().ensureDefaultLedger(ALICE);
        Ledger lc = service().create(ALICE, "合租", "COLLABORATIVE");
        AccountService accSvc = new AccountService(accountRepository, accountLedgerRepository,
                transactionRepository, loanRepository, loanRepaymentRepository, Clock.fixed(T0, ZONE));
        var acc = accSvc.create(ALICE, "公共钱包", "CASH", new java.math.BigDecimal("0.00"), 0,
                true, false, null, null, lc.getId());
        assertThat(accountLedgerRepository.findByAccountIdAndLedgerId(acc.getId(), lc.getId())).isPresent();

        service().delete(ALICE, lc.getId());

        // 账户保留（归属用户），仅账本关联被清除。
        assertThat(accountRepository.findByIdAndUserId(acc.getId(), ALICE)).isPresent();
        assertThat(accountLedgerRepository.findByAccountIdAndLedgerId(acc.getId(), lc.getId())).isEmpty();
    }
}
