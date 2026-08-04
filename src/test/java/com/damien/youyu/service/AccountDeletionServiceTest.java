package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 单元测试：{@link AccountDeletionService#requireDeletable(Long)} 的注销前置校验（需求 8.2）。
 *
 * <p>使用测试替身（Mockito）隔离仓储，验证两个协作牵连拦截条件（满足其一即抛
 * {@code DELETE_BLOCKED_COLLAB}）以及无牵连时的放行：</p>
 * <ol>
 *   <li>无牵连（拥有的账本无他人成员，账户无他人流水引用）→ 静默放行；</li>
 *   <li>拥有的协作账本仍有他人成员（{@code countByLedgerIdAndUserIdNot > 0}）→ DELETE_BLOCKED_COLLAB；</li>
 *   <li>拥有的账户被他人（{@code createdBy != userId}）流水引用 → DELETE_BLOCKED_COLLAB。</li>
 * </ol>
 */
class AccountDeletionServiceTest {

    private static final Long USER_ID = 7L;

    private LedgerRepository ledgerRepository;
    private LedgerMemberRepository memberRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private VerificationCodeService verificationCodeService;
    private WeChatClient weChatClient;
    private com.damien.youyu.repository.AccountLedgerRepository accountLedgerRepository;
    private com.damien.youyu.repository.TransactionTagRepository transactionTagRepository;
    private com.damien.youyu.repository.CategoryRepository categoryRepository;
    private com.damien.youyu.repository.BudgetRepository budgetRepository;
    private com.damien.youyu.repository.CategoryBudgetRepository categoryBudgetRepository;
    private com.damien.youyu.repository.LoanRepository loanRepository;
    private com.damien.youyu.repository.LoanRepaymentRepository loanRepaymentRepository;
    private com.damien.youyu.repository.ProjectRepository projectRepository;
    private com.damien.youyu.repository.MerchantRepository merchantRepository;
    private com.damien.youyu.repository.TagRepository tagRepository;
    private com.damien.youyu.repository.TransactionTemplateRepository templateRepository;
    private com.damien.youyu.repository.LedgerInviteRepository inviteRepository;
    private com.damien.youyu.repository.VerificationCodeRepository verificationCodeRepository;
    private com.damien.youyu.repository.InviteRelationRepository inviteRelationRepository;
    private com.damien.youyu.repository.GrowthEventRepository growthEventRepository;
    private com.damien.youyu.repository.UserGrowthRepository userGrowthRepository;
    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(LedgerRepository.class);
        memberRepository = mock(LedgerMemberRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        verificationCodeService = mock(VerificationCodeService.class);
        weChatClient = mock(WeChatClient.class);
        accountLedgerRepository = mock(com.damien.youyu.repository.AccountLedgerRepository.class);
        transactionTagRepository = mock(com.damien.youyu.repository.TransactionTagRepository.class);
        categoryRepository = mock(com.damien.youyu.repository.CategoryRepository.class);
        budgetRepository = mock(com.damien.youyu.repository.BudgetRepository.class);
        categoryBudgetRepository = mock(com.damien.youyu.repository.CategoryBudgetRepository.class);
        loanRepository = mock(com.damien.youyu.repository.LoanRepository.class);
        loanRepaymentRepository = mock(com.damien.youyu.repository.LoanRepaymentRepository.class);
        projectRepository = mock(com.damien.youyu.repository.ProjectRepository.class);
        merchantRepository = mock(com.damien.youyu.repository.MerchantRepository.class);
        tagRepository = mock(com.damien.youyu.repository.TagRepository.class);
        templateRepository = mock(com.damien.youyu.repository.TransactionTemplateRepository.class);
        inviteRepository = mock(com.damien.youyu.repository.LedgerInviteRepository.class);
        verificationCodeRepository = mock(com.damien.youyu.repository.VerificationCodeRepository.class);
        inviteRelationRepository = mock(com.damien.youyu.repository.InviteRelationRepository.class);
        growthEventRepository = mock(com.damien.youyu.repository.GrowthEventRepository.class);
        userGrowthRepository = mock(com.damien.youyu.repository.UserGrowthRepository.class);
        service = new AccountDeletionService(
                ledgerRepository, memberRepository, accountRepository, transactionRepository,
                userRepository, verificationCodeService, weChatClient,
                accountLedgerRepository, transactionTagRepository, categoryRepository,
                budgetRepository, categoryBudgetRepository, loanRepository, loanRepaymentRepository,
                projectRepository, merchantRepository, tagRepository, templateRepository, inviteRepository,
                verificationCodeRepository, inviteRelationRepository,
                growthEventRepository, userGrowthRepository,
                java.time.Clock.systemDefaultZone());
    }

    private Ledger ledger(Long id) {
        Ledger l = new Ledger();
        l.setId(id);
        l.setUserId(USER_ID);
        return l;
    }

    private Account account(Long id) {
        Account a = new Account();
        a.setId(id);
        a.setUserId(USER_ID);
        return a;
    }

    // ---------------- 条件一 + 条件二均不成立：放行 ----------------

    @Test
    void requireDeletable_noEntanglement_passes() {
        // 拥有一个账本、一个账户，但账本无他人成员、账户无他人流水引用。
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(ledger(100L)));
        when(memberRepository.countByLedgerIdAndUserIdNot(100L, USER_ID)).thenReturn(0L);
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(account(200L)));
        when(transactionRepository.existsByAccountReferencedByOtherUser(200L, USER_ID))
                .thenReturn(false);

        assertThatCode(() -> service.requireDeletable(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void requireDeletable_noLedgersNoAccounts_passes() {
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of());
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of());

        assertThatCode(() -> service.requireDeletable(USER_ID)).doesNotThrowAnyException();
    }

    // ---------------- 条件一：协作账本仍有他人成员 ----------------

    @Test
    void requireDeletable_ownedLedgerHasOtherMember_blocked() {
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(ledger(100L)));
        // 该账本除本人外仍有 2 名其他成员。
        when(memberRepository.countByLedgerIdAndUserIdNot(100L, USER_ID)).thenReturn(2L);

        ApiException ex = catchThrowableOfType(
                () -> service.requireDeletable(USER_ID), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("DELETE_BLOCKED_COLLAB");
    }

    // ---------------- 条件二：账户被他人流水引用 ----------------

    @Test
    void requireDeletable_accountReferencedByOthersTransactions_blocked() {
        // 账本无他人成员（条件一不触发），但账户被他人记账引用（条件二触发）。
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(ledger(100L)));
        when(memberRepository.countByLedgerIdAndUserIdNot(anyLong(), anyLong())).thenReturn(0L);
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(account(200L)));
        when(transactionRepository.existsByAccountReferencedByOtherUser(200L, USER_ID))
                .thenReturn(true);

        ApiException ex = catchThrowableOfType(
                () -> service.requireDeletable(USER_ID), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("DELETE_BLOCKED_COLLAB");
    }

    // ================= verifySecondFactor（注销二次验证门禁，需求 8.1）=================

    private User emailUser(String email) {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(email);
        return u;
    }

    private User wechatUser(String openid) {
        User u = new User();
        u.setId(USER_ID);
        u.setWxOpenid(openid);
        return u;
    }

    // ---------------- 邮箱身份用户：DELETE 验证码 ----------------

    @Test
    void verifySecondFactor_emailUser_correctDeleteCode_passes() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(emailUser("u@example.com")));
        when(verificationCodeService.verifyConsume("u@example.com", EmailCodePurpose.DELETE, "123456"))
                .thenReturn(true);

        assertThatCode(() -> service.verifySecondFactor(USER_ID, "123456", null))
                .doesNotThrowAnyException();
    }

    @Test
    void verifySecondFactor_emailUser_wrongCode_codeInvalid() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(emailUser("u@example.com")));
        when(verificationCodeService.verifyConsume("u@example.com", EmailCodePurpose.DELETE, "000000"))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(
                () -> service.verifySecondFactor(USER_ID, "000000", null), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("CODE_INVALID");
    }

    // ---------------- 纯微信用户：重新授权 ----------------

    @Test
    void verifySecondFactor_wechatUser_matchingOpenid_passes() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(wechatUser("openid-abc")));
        when(weChatClient.jscode2session("wx-code")).thenReturn(new WxSession("openid-abc", null));

        assertThatCode(() -> service.verifySecondFactor(USER_ID, null, "wx-code"))
                .doesNotThrowAnyException();
    }

    @Test
    void verifySecondFactor_wechatUser_mismatchedOpenid_rejected() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(wechatUser("openid-abc")));
        when(weChatClient.jscode2session("wx-code")).thenReturn(new WxSession("openid-other", null));

        ApiException ex = catchThrowableOfType(
                () -> service.verifySecondFactor(USER_ID, null, "wx-code"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("WX_LOGIN_FAILED");
    }

    @Test
    void verifySecondFactor_wechatUser_missingCode_wxCodeRequired() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(wechatUser("openid-abc")));

        ApiException ex = catchThrowableOfType(
                () -> service.verifySecondFactor(USER_ID, null, "  "), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("WX_CODE_REQUIRED");
    }

    // ---------------- 会话用户不存在 ----------------

    @Test
    void verifySecondFactor_missingUser_unauthenticated() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.verifySecondFactor(USER_ID, "123456", null), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
    }

    // ================= deleteAccount（单事务级联硬删，需求 8.3/8.4/8.5）=================

    /**
     * 验证级联删除按外键安全顺序调用各仓储：子/关联表先于父表，用户行最后。
     * （集成层的真实数据清零与身份释放见 {@code AccountDeletionCascadeTest}。）
     */
    @Test
    void deleteAccount_invokesRepositoriesInForeignKeySafeOrder() {
        User user = emailUser("alice@example.com");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(account(200L)));
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(ledger(100L)));
        when(transactionRepository.findAllIdsByUserId(USER_ID)).thenReturn(List.of(500L));

        service.deleteAccount(USER_ID);

        InOrder order = inOrder(transactionTagRepository, transactionRepository,
                categoryBudgetRepository, budgetRepository, loanRepository, categoryRepository,
                accountLedgerRepository, accountRepository, templateRepository, tagRepository,
                projectRepository, merchantRepository, inviteRepository, memberRepository,
                ledgerRepository, verificationCodeRepository, userRepository);

        // 交易-标签关联（交易子表）→ 交易 → 分类预算 → 分类 → account_ledger → 账户 → 账本目录 →
        // 邀请 → 成员 → 账本 → 验证码 → 用户行（最后）。
        order.verify(transactionTagRepository).deleteByTransactionIdIn(List.of(500L));
        order.verify(transactionRepository).hardDeleteByUserId(USER_ID);
        order.verify(categoryBudgetRepository).deleteByUserId(USER_ID);
        order.verify(budgetRepository).deleteByUserId(USER_ID);
        order.verify(loanRepository).deleteByUserId(USER_ID);
        order.verify(categoryRepository).deleteByUserId(USER_ID);
        order.verify(accountLedgerRepository).deleteByAccountIdIn(List.of(200L));
        order.verify(accountLedgerRepository).deleteByLedgerIdIn(List.of(100L));
        order.verify(accountRepository).deleteByUserId(USER_ID);
        order.verify(templateRepository).deleteByUserId(USER_ID);
        order.verify(tagRepository).deleteByUserId(USER_ID);
        order.verify(projectRepository).deleteByUserId(USER_ID);
        order.verify(merchantRepository).deleteByUserId(USER_ID);
        order.verify(inviteRepository).deleteByCreatedBy(USER_ID);
        order.verify(inviteRepository).deleteByLedgerIdIn(List.of(100L));
        order.verify(memberRepository).deleteByUserId(USER_ID);
        order.verify(ledgerRepository).deleteByUserId(USER_ID);
        order.verify(verificationCodeRepository).deleteByEmail("alice@example.com");
        order.verify(userRepository).delete(user);
    }

    /** 纯微信用户（无 email）注销：不触碰验证码表（无邮箱可释放），仍删除用户行释放 openid。 */
    @Test
    void deleteAccount_wechatOnlyUser_skipsVerificationCodeCleanup() {
        User user = wechatUser("openid-abc");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID)).thenReturn(List.of());
        when(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID)).thenReturn(List.of());
        when(transactionRepository.findAllIdsByUserId(USER_ID)).thenReturn(List.of());

        service.deleteAccount(USER_ID);

        verify(verificationCodeRepository, org.mockito.Mockito.never())
                .deleteByEmail(org.mockito.ArgumentMatchers.anyString());
        verify(userRepository).delete(user);
    }

    /** 会话用户不存在时抛 UNAUTHENTICATED，且不产生任何删除副作用。 */
    @Test
    void deleteAccount_missingUser_unauthenticated_noSideEffects() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.deleteAccount(USER_ID), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
        verify(userRepository, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.any());
        verify(transactionRepository, org.mockito.Mockito.never()).hardDeleteByUserId(anyLong());
    }
}
