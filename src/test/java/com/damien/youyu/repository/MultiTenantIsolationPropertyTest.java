package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.RandomGenerator;

/**
 * 多账本隔离的属性测试（Property 9/10 的账本版）：验证「所有查询固定携带 ledger_id 过滤」这一隔离契约。
 *
 * <p>业务数据的多租户边界为账本（{@code ledger_id}）。在真实 H2 + 真实 Spring Data 仓库上，
 * 用 jqwik 生成器产生随机的两账本数据集，每次迭代断言：</p>
 * <ul>
 *   <li>Property 9（读取隔离）：{@code findByLedgerId...} 只返回本账本数据；跨账本单条读取返回空。</li>
 *   <li>Property 10（写入归属）：落库实体的 {@code ledger_id} 恒为写入时指定的账本，且仅可被该账本检索到。</li>
 * </ul>
 *
 * <p>关联需求：2.2、2.3、2.4。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MultiTenantIsolationPropertyTest {

    private static final int ITERATIONS = 120;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private final RandomGenerator<Integer> smallCountGen =
            Arbitraries.integers().between(0, 4).generator(1000);
    private final RandomGenerator<BigDecimal> amountGen =
            Arbitraries.longs().between(1, 9_999_999)
                    .map(cents -> new BigDecimal(cents).movePointLeft(2))
                    .generator(1000);

    @Test
    void property9_multiLedgerReadIsolation() {
        Random rng = new Random(20250726L);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerA = iter * 2L + 1;
            long ledgerB = iter * 2L + 2;

            List<Long> accountsA = persistAccounts(ledgerA, smallCountGen.next(rng).value());
            List<Long> accountsB = persistAccounts(ledgerB, smallCountGen.next(rng).value());
            List<Long> categoriesA = persistCategories(ledgerA, smallCountGen.next(rng).value());
            List<Long> categoriesB = persistCategories(ledgerB, smallCountGen.next(rng).value());
            List<Long> txsA = persistTransactions(ledgerA, smallCountGen.next(rng).value(), rng);
            List<Long> txsB = persistTransactions(ledgerB, smallCountGen.next(rng).value(), rng);

            assertOwnedOnly(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(ledgerA),
                    accountsA, Account::getId, Account::getUserId, ledgerA);
            assertOwnedOnly(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(ledgerB),
                    accountsB, Account::getId, Account::getUserId, ledgerB);
            assertOwnedOnly(categoryRepository.findByLedgerId(ledgerA),
                    categoriesA, Category::getId, Category::getLedgerId, ledgerA);
            assertOwnedOnly(categoryRepository.findByLedgerId(ledgerB),
                    categoriesB, Category::getId, Category::getLedgerId, ledgerB);
            assertOwnedOnly(transactionRepository.findByLedgerId(ledgerA),
                    txsA, Transaction::getId, Transaction::getLedgerId, ledgerA);
            assertOwnedOnly(transactionRepository.findByLedgerId(ledgerB),
                    txsB, Transaction::getId, Transaction::getLedgerId, ledgerB);

            // 跨账本单条读取隔离：B 的资源用 A 的 ledger_id 读取返回空；用本账本读取可取到。
            for (Long bAccountId : accountsB) {
                assertThat(accountRepository.findByIdAndUserId(bAccountId, ledgerA)).isEmpty();
                assertThat(accountRepository.findByIdAndUserId(bAccountId, ledgerB)).isPresent();
            }
            for (Long bCategoryId : categoriesB) {
                assertThat(categoryRepository.findByIdAndLedgerId(bCategoryId, ledgerA)).isEmpty();
                assertThat(categoryRepository.findByIdAndLedgerId(bCategoryId, ledgerB)).isPresent();
            }
            for (Long bTxId : txsB) {
                assertThat(transactionRepository.findByIdAndLedgerId(bTxId, ledgerA)).isEmpty();
                assertThat(transactionRepository.findByIdAndLedgerId(bTxId, ledgerB)).isPresent();
            }
        }
    }

    @Test
    void property10_writeBindsToLedger() {
        Random rng = new Random(424242L);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledger = iter + 1;
            long otherLedger = 1_000_000L + iter;

            int n = 1 + smallCountGen.next(rng).value();
            List<Long> createdIds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Account account = new Account();
                account.setUserId(ledger);
                account.setName("acc" + i);
                account.setType(AccountType.CASH);
                account.setInitialBalance(BigDecimal.ZERO);
                account.setCurrentBalance(BigDecimal.ZERO);
                account.setSortOrder(i);
                account.setCreatedAt(FIXED_TIME);
                account.setUpdatedAt(FIXED_TIME);
                Account saved = accountRepository.save(account);
                assertThat(saved.getUserId()).isEqualTo(ledger);
                createdIds.add(saved.getId());
            }

            for (Long id : createdIds) {
                assertThat(accountRepository.findByIdAndUserId(id, ledger)).isPresent();
                assertThat(accountRepository.findByIdAndUserId(id, otherLedger)).isEmpty();
            }
        }
    }

    private <T> void assertOwnedOnly(
            List<T> found,
            List<Long> expectedIds,
            java.util.function.Function<T, Long> idOf,
            java.util.function.Function<T, Long> ledgerIdOf,
            Long ownerLedgerId) {
        assertThat(found).hasSize(expectedIds.size());
        assertThat(found).allSatisfy(e -> assertThat(ledgerIdOf.apply(e)).isEqualTo(ownerLedgerId));
        assertThat(found.stream().map(idOf).toList())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    private List<Long> persistAccounts(Long ledgerId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account a = new Account();
            a.setUserId(ledgerId);
            a.setName("acc" + i);
            a.setType(AccountType.CASH);
            a.setInitialBalance(BigDecimal.ZERO);
            a.setCurrentBalance(BigDecimal.ZERO);
            a.setSortOrder(i);
            a.setCreatedAt(FIXED_TIME);
            a.setUpdatedAt(FIXED_TIME);
            ids.add(accountRepository.save(a).getId());
        }
        return ids;
    }

    private List<Long> persistCategories(Long ledgerId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category c = new Category();
            c.setLedgerId(ledgerId);
            c.setKind(CategoryKind.EXPENSE);
            c.setParentId(null);
            c.setName("cat" + i);
            c.setCreatedAt(FIXED_TIME);
            c.setUpdatedAt(FIXED_TIME);
            ids.add(categoryRepository.save(c).getId());
        }
        return ids;
    }

    private List<Long> persistTransactions(Long ledgerId, int count, Random rng) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            t.setLedgerId(ledgerId);
            t.setType(TransactionType.EXPENSE);
            t.setAmount(amountGen.next(rng).value());
            t.setAccountId(null);
            t.setCategoryId(null);
            t.setOccurredAt(FIXED_TIME);
            t.setCreatedAt(FIXED_TIME);
            t.setUpdatedAt(FIXED_TIME);
            ids.add(transactionRepository.save(t).getId());
        }
        return ids;
    }
}
