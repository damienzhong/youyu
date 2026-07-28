package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;

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

    private User newUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
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

        User reloadedUser = userRepository.findByUsername("alice").orElseThrow();
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
}
