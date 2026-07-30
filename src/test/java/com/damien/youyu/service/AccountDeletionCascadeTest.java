package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Budget;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryBudget;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerInvite;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.domain.Merchant;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Project;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Tag;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionTag;
import com.damien.youyu.domain.TransactionTemplate;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.domain.VerificationCode;
import com.damien.youyu.repository.AccountLedgerRepository;
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
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.wechat.WeChatClient;

/**
 * 集成测试：{@link AccountDeletionService#deleteAccount(Long)} 的单事务级联硬删（任务 5.3，需求 8.3/8.4/8.5）。
 *
 * <p>H2（create-drop）+ 真实 Repository。播种一名用户在全部相关表中的数据（账本/账户/账户关联/交易/
 * 交易标签/分类/预算/分类预算/借贷/项目/商家/标签/模板/邀请/成员/验证码），调用 {@code deleteAccount}，
 * 断言本人各表数据全部清零、{@code email}/{@code wx_openid} 立即释放（可重新注册复用）；同时播种第二名
 * 用户的数据以验证「只删本人、不误删他人」。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountDeletionCascadeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 1, 12, 0);
    private static final String EMAIL = "alice@example.com";
    private static final String OPENID = "wx-openid-alice";

    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private LedgerMemberRepository memberRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountLedgerRepository accountLedgerRepository;
    @Autowired private TransactionTagRepository transactionTagRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BudgetRepository budgetRepository;
    @Autowired private CategoryBudgetRepository categoryBudgetRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private TransactionTemplateRepository templateRepository;
    @Autowired private LedgerInviteRepository inviteRepository;
    @Autowired private VerificationCodeRepository verificationCodeRepository;

    private AccountDeletionService service() {
        return new AccountDeletionService(
                ledgerRepository, memberRepository, accountRepository, transactionRepository,
                userRepository, mock(VerificationCodeService.class), mock(WeChatClient.class),
                accountLedgerRepository, transactionTagRepository, categoryRepository,
                budgetRepository, categoryBudgetRepository, loanRepository, projectRepository,
                merchantRepository, tagRepository, templateRepository, inviteRepository,
                verificationCodeRepository);
    }

    /** 播种一名用户名下全部相关表数据，返回其 userId。email/openid 唯一，便于验证释放。 */
    private Long seedFullUser(String email, String openid) {
        User user = new User();
        user.setEmail(email);
        user.setWxOpenid(openid);
        user.setNickname("Alice");
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(NOW);
        user.setPlanExpiresAt(NOW.plusDays(365));
        user.setCreatedAt(NOW);
        user.setUpdatedAt(NOW);
        user = userRepository.save(user);
        Long uid = user.getId();

        Ledger ledger = new Ledger();
        ledger.setUserId(uid);
        ledger.setName("我的账本");
        ledger.setType(Ledger.TYPE_COLLABORATIVE);
        ledger.setDefault(true);
        ledger.setCreatedAt(NOW);
        ledger.setUpdatedAt(NOW);
        ledger = ledgerRepository.save(ledger);
        Long lid = ledger.getId();

        LedgerMember member = new LedgerMember();
        member.setLedgerId(lid);
        member.setUserId(uid);
        member.setRole(LedgerMember.ROLE_OWNER);
        member.setCreatedAt(NOW);
        memberRepository.save(member);

        LedgerInvite invite = new LedgerInvite();
        invite.setCode("INV-" + openid);
        invite.setLedgerId(lid);
        invite.setCreatedBy(uid);
        invite.setExpiresAt(NOW.plusDays(7));
        invite.setCreatedAt(NOW);
        inviteRepository.save(invite);

        Account account = new Account();
        account.setUserId(uid);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(new BigDecimal("100.00"));
        account.setCurrentBalance(new BigDecimal("100.00"));
        account.setCreatedAt(NOW);
        account.setUpdatedAt(NOW);
        account = accountRepository.save(account);
        Long aid = account.getId();

        AccountLedger al = new AccountLedger();
        al.setAccountId(aid);
        al.setLedgerId(lid);
        al.setCreatedAt(NOW);
        accountLedgerRepository.save(al);

        Category category = new Category();
        category.setUserId(uid);
        category.setLedgerId(lid);
        category.setKind(CategoryKind.EXPENSE);
        category.setName("餐饮");
        category.setCreatedAt(NOW);
        category.setUpdatedAt(NOW);
        category = categoryRepository.save(category);
        Long cid = category.getId();

        Tag tag = new Tag();
        tag.setUserId(uid);
        tag.setLedgerId(lid);
        tag.setName("报销");
        tag.setSortOrder(0);
        tag.setCreatedAt(NOW);
        tag.setUpdatedAt(NOW);
        tag = tagRepository.save(tag);
        Long tagId = tag.getId();

        Transaction tx = new Transaction();
        tx.setUserId(uid);
        tx.setLedgerId(lid);
        tx.setCreatedBy(uid);
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(new BigDecimal("20.00"));
        tx.setAccountId(aid);
        tx.setCategoryId(cid);
        tx.setOccurredAt(NOW);
        tx.setCreatedAt(NOW);
        tx.setUpdatedAt(NOW);
        tx = transactionRepository.save(tx);

        transactionTagRepository.save(new TransactionTag(tx.getId(), tagId));

        Budget budget = new Budget();
        budget.setUserId(uid);
        budget.setLedgerId(lid);
        budget.setMonth("2025-06");
        budget.setAmount(new BigDecimal("3000.00"));
        budget.setCreatedAt(NOW);
        budget.setUpdatedAt(NOW);
        budgetRepository.save(budget);

        CategoryBudget cb = new CategoryBudget();
        cb.setUserId(uid);
        cb.setLedgerId(lid);
        cb.setMonth("2025-06");
        cb.setCategoryId(cid);
        cb.setAmount(new BigDecimal("500.00"));
        cb.setCreatedAt(NOW);
        cb.setUpdatedAt(NOW);
        categoryBudgetRepository.save(cb);

        Loan loan = new Loan();
        loan.setUserId(uid);
        loan.setLedgerId(lid);
        loan.setDirection(LoanDirection.LEND);
        loan.setCounterparty("Bob");
        loan.setAmount(new BigDecimal("200.00"));
        loan.setOccurredAt(NOW);
        loan.setCreatedAt(NOW);
        loan.setUpdatedAt(NOW);
        loanRepository.save(loan);

        Project project = new Project();
        project.setUserId(uid);
        project.setLedgerId(lid);
        project.setName("装修");
        project.setSortOrder(0);
        project.setCreatedAt(NOW);
        project.setUpdatedAt(NOW);
        projectRepository.save(project);

        Merchant merchant = new Merchant();
        merchant.setUserId(uid);
        merchant.setLedgerId(lid);
        merchant.setName("星巴克");
        merchant.setSortOrder(0);
        merchant.setCreatedAt(NOW);
        merchant.setUpdatedAt(NOW);
        merchantRepository.save(merchant);

        TransactionTemplate template = new TransactionTemplate();
        template.setUserId(uid);
        template.setLedgerId(lid);
        template.setName("午餐");
        template.setType("expense");
        template.setSortOrder(0);
        template.setCreatedAt(NOW);
        template.setUpdatedAt(NOW);
        templateRepository.save(template);

        VerificationCode vc = new VerificationCode();
        vc.setEmail(email);
        vc.setPurpose(EmailCodePurpose.DELETE);
        vc.setCode("123456");
        vc.setExpiresAt(NOW.plusMinutes(10));
        vc.setCreatedAt(NOW);
        verificationCodeRepository.save(vc);

        return uid;
    }

    @Test
    void deleteAccount_hardDeletesAllOwnedDataAndReleasesIdentity() {
        Long uid = seedFullUser(EMAIL, OPENID);

        service().deleteAccount(uid);

        // 本人各表数据全部清零。
        assertThat(userRepository.findById(uid)).isEmpty();
        assertThat(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(uid)).isEmpty();
        assertThat(memberRepository.findByUserId(uid)).isEmpty();
        assertThat(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(uid)).isEmpty();
        assertThat(transactionRepository.findAllIdsByUserId(uid)).isEmpty();
        assertThat(accountLedgerRepository.count()).isZero();
        assertThat(transactionTagRepository.count()).isZero();
        assertThat(categoryRepository.count()).isZero();
        assertThat(budgetRepository.count()).isZero();
        assertThat(categoryBudgetRepository.count()).isZero();
        assertThat(loanRepository.count()).isZero();
        assertThat(projectRepository.count()).isZero();
        assertThat(merchantRepository.count()).isZero();
        assertThat(tagRepository.count()).isZero();
        assertThat(templateRepository.count()).isZero();
        assertThat(inviteRepository.count()).isZero();
        assertThat(transactionRepository.count()).isZero();
        // 验证码按邮箱清理（需求 8.4：干净释放邮箱身份）。
        assertThat(verificationCodeRepository.count()).isZero();

        // email / wx_openid 立即释放：查不到且可被新账号复用（需求 8.4）。
        assertThat(userRepository.findByEmail(EMAIL)).isEmpty();
        assertThat(userRepository.findByWxOpenid(OPENID)).isEmpty();
        assertThat(userRepository.existsByEmail(EMAIL)).isFalse();

        User reused = new User();
        reused.setEmail(EMAIL);
        reused.setWxOpenid(OPENID);
        reused.setPlan(Plan.FREE);
        reused.setRole(Role.USER);
        reused.setPlanStartedAt(NOW);
        reused.setPlanExpiresAt(NOW.plusDays(365));
        reused.setCreatedAt(NOW);
        reused.setUpdatedAt(NOW);
        // 唯一键已释放：重新注册同 email/openid 不冲突。
        User saved = userRepository.saveAndFlush(reused);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void deleteAccount_doesNotTouchOtherUsersData() {
        Long alice = seedFullUser(EMAIL, OPENID);
        Long bob = seedFullUser("bob@example.com", "wx-openid-bob");

        service().deleteAccount(alice);

        // Bob 的数据完好无损。
        assertThat(userRepository.findById(bob)).isPresent();
        assertThat(ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(bob)).hasSize(1);
        assertThat(memberRepository.findByUserId(bob)).hasSize(1);
        assertThat(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(bob)).hasSize(1);
        assertThat(transactionRepository.findAllIdsByUserId(bob)).hasSize(1);
        assertThat(transactionTagRepository.count()).isEqualTo(1);
        assertThat(accountLedgerRepository.count()).isEqualTo(1);
        assertThat(budgetRepository.count()).isEqualTo(1);
        assertThat(categoryBudgetRepository.count()).isEqualTo(1);
        assertThat(loanRepository.count()).isEqualTo(1);
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(merchantRepository.count()).isEqualTo(1);
        assertThat(tagRepository.count()).isEqualTo(1);
        assertThat(templateRepository.count()).isEqualTo(1);
        assertThat(inviteRepository.count()).isEqualTo(1);
        assertThat(verificationCodeRepository.count()).isEqualTo(1);
    }
}
