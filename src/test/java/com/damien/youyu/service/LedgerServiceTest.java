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

    private LedgerService service() {
        return serviceAt(T0);
    }

    private LedgerService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZONE);
        AccountService accountService =
                new AccountService(accountRepository, accountLedgerRepository, transactionRepository,
                        loanRepository, loanRepaymentRepository, clock);
        return new LedgerService(ledgerRepository, categoryRepository, accountRepository,
                accountLedgerRepository, transactionRepository, budgetRepository, categoryBudgetRepository,
                loanRepository, loanRepaymentRepository, memberRepository, inviteRepository, templateRepository,
                projectRepository, merchantRepository, tagRepository, transactionTagRepository, accountService,
                new InviteCodeGenerator(), clock);
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
