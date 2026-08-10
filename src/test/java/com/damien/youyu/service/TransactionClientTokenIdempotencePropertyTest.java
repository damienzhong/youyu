package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: offline-sync, Property 1: 创建幂等
 *
 * <p>对任意合法收支 payload，用<b>同一</b> {@code clientToken} 调用 {@link TransactionService#create}
 * N≥2 次：数据库<b>仅一条</b>该交易、对应账户余额<b>仅变化一次</b>（{@code +amount} 收入 / {@code −amount}
 * 支出）、每次返回的交易 id <b>恒相同</b>。{@code clientToken} 为 null / 空白时不参与去重（与今天一致）。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实 {@link TransactionService}、不用测试级事务</h2>
 * <p>与 {@code RecurringConfirmConservationPropertyTest} 同源：幂等去重依赖真实的
 * {@code findByCreatedByAndClientToken} 查重 + 单事务原子余额更新 + 真实提交，故注入<b>真实</b>交易服务对
 * 真实 H2（{@code MODE=MySQL}）读写，不加测试级 {@code @Transactional}（那会回滚、掩盖真实提交与去重），
 * 清理改为每个 try 前显式清库（{@link #resetAndInject()}），并用独立命名内存库避免污染其它切片测试。</p>
 *
 * <p>jqwik 属性方法不经 JUnit Jupiter 引擎、{@code SpringExtension} 不生效：依赖注入由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文静态缓存复用）。</p>
 *
 * <p><strong>Validates: Requirements 6.2, 6.3, 6.4, 9.4</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-offline-idem-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class TransactionClientTokenIdempotencePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 1L;
    private static final BigDecimal INITIAL = new BigDecimal("1000000.00");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private Long account;
    private Long expenseCat;
    private Long incomeCat;

    @BeforeTry
    void resetAndInject() throws Exception {
        new TestContextManager(TransactionClientTokenIdempotencePropertyTest.class).prepareTestInstance(this);
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        account = seedAccount(INITIAL);
        linkAccountToLedger(account, LEDGER);
        expenseCat = seedCategory(CategoryKind.EXPENSE, "餐饮");
        incomeCat = seedCategory(CategoryKind.INCOME, "工资");
    }

    // Feature: offline-sync, Property 1: 创建幂等
    @Property(tries = 100)
    void sameClientTokenCreatesExactlyOnceWithSingleBalanceChange(@ForAll("scenarios") Scenario s) {
        boolean income = "income".equals(s.type());
        Long categoryId = income ? incomeCat : expenseCat;
        BigDecimal delta = income ? s.amount() : s.amount().negate();
        LocalDateTime when = LocalDateTime.ofInstant(NOW, ZONE);

        // 用同一 clientToken 连续创建 N 次（N∈[2,5]）。
        Long firstId = null;
        for (int i = 0; i < s.repeat(); i++) {
            Transaction tx = transactionService.create(
                    ALICE, LEDGER, s.type(), s.amount(), account, categoryId,
                    when, s.note(), null, null, null, s.clientToken());
            if (firstId == null) {
                firstId = tx.getId();
            } else {
                // 每次返回的 id 恒相同（幂等返回既有记录）。
                assertThat(tx.getId()).as("重复 clientToken 应返回同一交易 id").isEqualTo(firstId);
            }
        }

        // 仅一条落库。
        assertThat(transactionRepository.count()).as("同一 clientToken 至多落一笔").isEqualTo(1);
        // 余额仅变化一次。
        assertThat(balanceOf(account))
                .as("账户余额仅变化一次")
                .isEqualByComparingTo(INITIAL.add(delta));
    }

    /**
     * clientToken 为 null 时不去重：多次创建产生多条流水、余额多次变化（与今天行为一致，兼容回归）。
     */
    @Property(tries = 100)
    void nullClientTokenDoesNotDedup(@ForAll("scenarios") Scenario s) {
        boolean income = "income".equals(s.type());
        Long categoryId = income ? incomeCat : expenseCat;
        BigDecimal delta = income ? s.amount() : s.amount().negate();
        LocalDateTime when = LocalDateTime.ofInstant(NOW, ZONE);
        int n = s.repeat();
        for (int i = 0; i < n; i++) {
            transactionService.create(ALICE, LEDGER, s.type(), s.amount(), account, categoryId,
                    when, s.note(), null, null, null, null);
        }
        assertThat(transactionRepository.count()).as("无 clientToken 时不去重").isEqualTo(n);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL.add(delta.multiply(new BigDecimal(n))));
    }

    // =====================================================================
    // 生成器与夹具
    // =====================================================================

    record Scenario(String type, BigDecimal amount, String note, String clientToken, int repeat) {}

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<String> type = Arbitraries.of("expense", "income");
        Arbitrary<BigDecimal> amount = Arbitraries.integers().between(1, 99_999_99)
                .map(cents -> new BigDecimal(cents).movePointLeft(2));
        Arbitrary<String> note = Arbitraries.strings().withCharRange('a', 'z').ofMaxLength(20).injectNull(0.3);
        Arbitrary<String> token = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(6).ofMaxLength(40)
                .map(sfx -> "ct_" + sfx);
        Arbitrary<Integer> repeat = Arbitraries.integers().between(2, 5);
        return Combinators.combine(type, amount, note, token, repeat).as(Scenario::new);
    }

    private Long seedAccount(BigDecimal balance) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account a = new Account();
        a.setUserId(ALICE);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(balance);
        a.setCurrentBalance(balance);
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a).getId();
    }

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        AccountLedger al = new AccountLedger();
        al.setAccountId(accountId);
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
    }

    private Long seedCategory(CategoryKind kind, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category c = new Category();
        c.setLedgerId(LEDGER);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c).getId();
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }
}
