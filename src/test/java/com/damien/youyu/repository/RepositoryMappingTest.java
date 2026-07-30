package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.domain.VerificationCode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 JPA 实体映射正确、BigDecimal 金额保真，以及仓库查询固定携带 user_id 过滤（多租户隔离）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryMappingTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    private User newUser(String nickname) {
        User u = new User();
        u.setEmail(nickname + "@example.com");
        u.setNickname(nickname);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    private Account newAccount(Long ledgerId, String name, AccountType type, String initial, int sortOrder) {
        Account a = new Account();
        a.setUserId(ledgerId);
        a.setName(name);
        a.setType(type);
        a.setInitialBalance(new BigDecimal(initial));
        a.setCurrentBalance(new BigDecimal(initial));
        a.setSortOrder(sortOrder);
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private Category newCategory(Long ledgerId, CategoryKind kind, Long parentId, String name) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setParentId(parentId);
        c.setName(name);
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private Transaction newExpense(Long ledgerId, Long accountId, Long categoryId, String amount) {
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(new BigDecimal(amount));
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setOccurredAt(LocalDateTime.of(2025, 6, 1, 12, 30, 0));
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 30, 0);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return transactionRepository.save(t);
    }

    @Test
    void persistsEnumsAndBigDecimalWithScale() {
        User user = newUser("alice");
        Account acc = newAccount(user.getId(), "现金", AccountType.CASH, "100.55", 0);

        Account reloaded = accountRepository.findById(acc.getId()).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo(AccountType.CASH);
        // BigDecimal 保留两位小数，金额保真
        assertThat(reloaded.getInitialBalance()).isEqualByComparingTo("100.55");
        assertThat(reloaded.getCurrentBalance()).isEqualByComparingTo("100.55");

        User reloadedUser = userRepository.findByEmail("alice@example.com").orElseThrow();
        assertThat(reloadedUser.getNickname()).isEqualTo("alice");
        assertThat(reloadedUser.getPlan()).isEqualTo(Plan.FREE);
        assertThat(reloadedUser.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void accountQueriesAreUserScopedAndOrdered() {
        User alice = newUser("alice2");
        User bob = newUser("bob2");
        newAccount(alice.getId(), "银行卡", AccountType.BANK_CARD, "0.00", 2);
        newAccount(alice.getId(), "现金", AccountType.CASH, "0.00", 1);
        newAccount(bob.getId(), "支付宝", AccountType.ALIPAY, "0.00", 0);

        var aliceAccounts = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(alice.getId());
        assertThat(aliceAccounts).hasSize(2);
        assertThat(aliceAccounts.get(0).getName()).isEqualTo("现金");
        assertThat(aliceAccounts.get(1).getName()).isEqualTo("银行卡");
        assertThat(accountRepository.countByUserId(alice.getId())).isEqualTo(2);

        // 默认账户回退：排序第一
        assertThat(accountRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(alice.getId()))
                .get()
                .extracting(Account::getName)
                .isEqualTo("现金");

        // 越权：用 bob 的 id 无法通过 alice 的 user_id 取到账户
        Long bobAccountId = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(bob.getId()).get(0).getId();
        assertThat(accountRepository.findByIdAndUserId(bobAccountId, alice.getId())).isEmpty();
        assertThat(accountRepository.findByIdAndUserId(bobAccountId, bob.getId())).isPresent();
    }

    @Test
    void transactionReferenceChecksAreUserScoped() {
        User user = newUser("carol");
        Account acc = newAccount(user.getId(), "现金", AccountType.CASH, "0.00", 0);
        Category food = newCategory(user.getId(), CategoryKind.EXPENSE, null, "餐饮");
        Transaction tx = newExpense(user.getId(), acc.getId(), food.getId(), "23.50");

        // 交易类型枚举以小写编码存储并正确回读
        Transaction reloaded = transactionRepository.findByIdAndLedgerId(tx.getId(), user.getId()).orElseThrow();
        assertThat(reloaded.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("23.50");

        // 账户被引用 -> 不可删除校验为 true
        assertThat(transactionRepository.existsByAccountReferenced(acc.getId())).isTrue();
        // 分类被引用
        assertThat(transactionRepository.existsByLedgerIdAndCategoryId(user.getId(), food.getId())).isTrue();

        // 分页倒序 + user_id 过滤
        var page = transactionRepository.findByLedgerIdOrderByOccurredAtDescIdDesc(
                user.getId(), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);

        // 最近一笔（默认账户来源）
        assertThat(transactionRepository.findFirstByLedgerIdOrderByOccurredAtDescIdDesc(user.getId()))
                .get()
                .extracting(Transaction::getAccountId)
                .isEqualTo(acc.getId());
    }

    @Test
    void categoryDuplicateAndChildChecksAreUserScoped() {
        User user = newUser("dave");
        Category parent = newCategory(user.getId(), CategoryKind.EXPENSE, null, "餐饮");
        newCategory(user.getId(), CategoryKind.EXPENSE, parent.getId(), "外卖");

        // 父级范围内重名（子分类）
        assertThat(categoryRepository.existsByLedgerIdAndKindAndParentIdAndName(
                user.getId(), CategoryKind.EXPENSE, parent.getId(), "外卖")).isTrue();
        // 父分类(parent_id 为 NULL)重名的应用层补充校验
        assertThat(categoryRepository.existsByLedgerIdAndKindAndParentIdIsNullAndName(
                user.getId(), CategoryKind.EXPENSE, "餐饮")).isTrue();
        // 含子分类 -> 禁止删除校验为 true
        assertThat(categoryRepository.existsByLedgerIdAndParentId(user.getId(), parent.getId())).isTrue();

        // 收入分类与支出分类各自独立
        assertThat(categoryRepository.findByLedgerIdAndKind(user.getId(), CategoryKind.INCOME)).isEmpty();
        assertThat(categoryRepository.findByLedgerIdAndKind(user.getId(), CategoryKind.EXPENSE)).hasSize(2);
    }

    // ---- 无密码用户模型：email/nickname 映射与邮箱唯一性（需求 4、9.1、9.5） ----

    @Test
    void userMapsEmailAndNicknameWithoutPasswordFields() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        User u = new User();
        u.setEmail("mapper@example.com");
        u.setNickname("映射测试");
        u.setWxOpenid("openid-mapper-1");
        u.setWxUnionid("unionid-mapper-1");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userRepository.save(u);

        // 通过邮箱身份回读，确认各字段映射正确（无 username/passwordHash 概念）
        User reloaded = userRepository.findByEmail("mapper@example.com").orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("mapper@example.com");
        assertThat(reloaded.getNickname()).isEqualTo("映射测试");
        assertThat(reloaded.getWxOpenid()).isEqualTo("openid-mapper-1");
        assertThat(reloaded.getWxUnionid()).isEqualTo("unionid-mapper-1");
        assertThat(reloaded.getPlan()).isEqualTo(Plan.FREE);
        assertThat(reloaded.getRole()).isEqualTo(Role.USER);
        assertThat(reloaded.getPlanStartedAt()).isEqualTo(now);
        assertThat(reloaded.getPlanExpiresAt()).isEqualTo(now.plusDays(365));
    }

    @Test
    void emailIsGloballyUnique() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        User first = new User();
        first.setEmail("dup@example.com");
        first.setNickname("first");
        first.setPlan(Plan.FREE);
        first.setRole(Role.USER);
        first.setPlanStartedAt(now);
        first.setPlanExpiresAt(now.plusDays(365));
        first.setCreatedAt(now);
        first.setUpdatedAt(now);
        userRepository.save(first);

        assertThat(userRepository.existsByEmail("dup@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();

        // 第二个账号使用相同 email 时，唯一约束在 flush 时拒绝
        User second = new User();
        second.setEmail("dup@example.com");
        second.setNickname("second");
        second.setPlan(Plan.FREE);
        second.setRole(Role.USER);
        second.setPlanStartedAt(now);
        second.setPlanExpiresAt(now.plusDays(365));
        second.setCreatedAt(now);
        second.setUpdatedAt(now);

        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- VerificationCode 实体↔表一致性与仓库查询（需求 1、2、9.5） ----

    private VerificationCode newCode(String email, EmailCodePurpose purpose, String code,
            String ip, LocalDateTime createdAt, LocalDateTime expiresAt) {
        VerificationCode vc = new VerificationCode();
        vc.setEmail(email);
        vc.setPurpose(purpose);
        vc.setCode(code);
        vc.setIp(ip);
        vc.setConsumed(false);
        vc.setAttemptCount(0);
        vc.setCreatedAt(createdAt);
        vc.setExpiresAt(expiresAt);
        return verificationCodeRepository.save(vc);
    }

    @Test
    void verificationCodeRoundTripsAllFieldsIncludingPurposeEnum() {
        LocalDateTime created = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        LocalDateTime expires = created.plusMinutes(10);
        VerificationCode saved = newCode("vc@example.com", EmailCodePurpose.BIND, "123456",
                "203.0.113.7", created, expires);
        saved.setAttemptCount(3);
        verificationCodeRepository.saveAndFlush(saved);

        VerificationCode reloaded = verificationCodeRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("vc@example.com");
        // purpose 以字符串存储并正确回读为枚举
        assertThat(reloaded.getPurpose()).isEqualTo(EmailCodePurpose.BIND);
        assertThat(reloaded.getCode()).isEqualTo("123456");
        assertThat(reloaded.getIp()).isEqualTo("203.0.113.7");
        assertThat(reloaded.isConsumed()).isFalse();
        assertThat(reloaded.getAttemptCount()).isEqualTo(3);
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    void findsLatestUnconsumedCodeByEmailAndPurpose() {
        LocalDateTime created = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        LocalDateTime expires = created.plusMinutes(10);
        VerificationCode older = newCode("login@example.com", EmailCodePurpose.LOGIN, "111111",
                "10.0.0.1", created, expires);
        VerificationCode newer = newCode("login@example.com", EmailCodePurpose.LOGIN, "222222",
                "10.0.0.1", created.plusMinutes(1), expires.plusMinutes(1));

        // 取最近一条未消费的码（按 id 倒序）
        assertThat(verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc("login@example.com", EmailCodePurpose.LOGIN))
                .get()
                .extracting(VerificationCode::getId)
                .isEqualTo(newer.getId());

        // 消费最新一条后，回退到较早的未消费码
        newer.setConsumed(true);
        verificationCodeRepository.saveAndFlush(newer);
        assertThat(verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc("login@example.com", EmailCodePurpose.LOGIN))
                .get()
                .extracting(VerificationCode::getId)
                .isEqualTo(older.getId());

        // 不同用途互不影响
        assertThat(verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc("login@example.com", EmailCodePurpose.DELETE))
                .isEmpty();
    }

    @Test
    void cooldownAndIpRateLimitQueriesBehaveUnderH2() {
        LocalDateTime base = LocalDateTime.of(2025, 6, 1, 12, 0, 0);
        LocalDateTime expires = base.plusMinutes(10);
        // 冷却窗口：同 (email, purpose) 在 since 之后是否存在发码
        newCode("cool@example.com", EmailCodePurpose.LOGIN, "333333", "198.51.100.5", base, expires);

        assertThat(verificationCodeRepository.existsByEmailAndPurposeAndCreatedAtAfter(
                "cool@example.com", EmailCodePurpose.LOGIN, base.minusSeconds(60))).isTrue();
        assertThat(verificationCodeRepository.existsByEmailAndPurposeAndCreatedAtAfter(
                "cool@example.com", EmailCodePurpose.LOGIN, base.plusSeconds(1))).isFalse();
        assertThat(verificationCodeRepository.existsByEmailAndPurposeAndCreatedAtAfter(
                "cool@example.com", EmailCodePurpose.BIND, base.minusSeconds(60))).isFalse();

        // IP 计数：同一 IP 在 since 之后的发码次数
        newCode("a@example.com", EmailCodePurpose.LOGIN, "444444", "198.51.100.5", base.plusSeconds(10), expires);
        newCode("b@example.com", EmailCodePurpose.LOGIN, "555555", "198.51.100.5", base.plusSeconds(20), expires);
        newCode("c@example.com", EmailCodePurpose.LOGIN, "666666", "198.51.100.99", base.plusSeconds(20), expires);

        assertThat(verificationCodeRepository.countByIpAndCreatedAtAfter(
                "198.51.100.5", base.minusMinutes(1))).isEqualTo(3);
        assertThat(verificationCodeRepository.countByIpAndCreatedAtAfter(
                "198.51.100.5", base.plusSeconds(15))).isEqualTo(1);
        assertThat(verificationCodeRepository.countByIpAndCreatedAtAfter(
                "198.51.100.99", base.minusMinutes(1))).isEqualTo(1);
    }
}
