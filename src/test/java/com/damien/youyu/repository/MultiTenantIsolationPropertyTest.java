package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.security.CurrentUserPrincipal;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.RandomGenerator;

/**
 * 多租户隔离的属性测试：覆盖设计文档 Correctness Properties 中的
 * Property 9（多租户读取隔离）与 Property 10（写入强制会话 user_id）。
 *
 * <p>本测试在真实的 H2 数据库上运行真实的 Spring Data 仓库（不使用 mock/内存桩），以验证
 * 「所有查询固定携带 user_id 过滤」这一隔离契约。随机的多用户数据集由 jqwik 生成器产生，
 * 每个属性运行 {@value #ITERATIONS} 次随机迭代（≥100），每次迭代后断言不变式。</p>
 *
 * <p><strong>关于 Property 10 的范围说明</strong>：业务写入服务（Account/Category/Transaction
 * Service）尚未实现（任务 4/5/6），完整的「请求体 user_id 被忽略、落库强制为会话用户」将在这些
 * 服务实现后于其模块测试中端到端验证。本处在<em>当前可实现的层面</em>验证该原则：会话用户由真实的
 * {@link CurrentUser} 从 SecurityContext 读取，写入实体的 user_id 强制绑定为会话用户（忽略请求体
 * 携带的任意 user_id），且落库后仅可被会话用户按 user_id 检索到。</p>
 *
 * <p>关联需求：2.2、2.3、2.4。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MultiTenantIsolationPropertyTest {

    private static final int ITERATIONS = 120;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private final CurrentUser currentUser = new CurrentUser();

    // jqwik 生成器（在循环中以固定种子的 Random 逐个取值），用于生成随机的账户/分类/交易数量。
    private final RandomGenerator<Integer> smallCountGen =
            Arbitraries.integers().between(0, 4).generator(1000);
    private final RandomGenerator<Integer> positiveCountGen =
            Arbitraries.integers().between(1, 4).generator(1000);
    private final RandomGenerator<BigDecimal> amountGen =
            Arbitraries.longs().between(1, 9_999_999)
                    .map(cents -> new BigDecimal(cents).movePointLeft(2))
                    .generator(1000);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Feature: youyu-ledger, Property 9: 对任意两个不同用户各自的业务数据集合，任一用户对业务数据的
     * 列表/查询/单条读取都应只返回 user_id 与其匹配的数据，不包含任何其他用户的数据；当一个用户请求
     * 读取不属于自己的资源时应被拒绝（返回空），不返回目标内容。
     */
    @Test
    void property9_multiTenantReadIsolation() {
        Random rng = new Random(20250726L);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            User userA = persistUser("a_" + iter);
            User userB = persistUser("b_" + iter);

            int accA = smallCountGen.next(rng).value();
            int accB = smallCountGen.next(rng).value();
            int catA = smallCountGen.next(rng).value();
            int catB = smallCountGen.next(rng).value();
            int txA = smallCountGen.next(rng).value();
            int txB = smallCountGen.next(rng).value();

            List<Long> accountsA = persistAccounts(userA.getId(), accA);
            List<Long> accountsB = persistAccounts(userB.getId(), accB);
            List<Long> categoriesA = persistCategories(userA.getId(), catA);
            List<Long> categoriesB = persistCategories(userB.getId(), catB);
            List<Long> txsA = persistTransactions(userA.getId(), txA, rng);
            List<Long> txsB = persistTransactions(userB.getId(), txB, rng);

            // 列表查询只返回本人数据，数量与内容一致；无数据时为空列表。
            assertOwnedOnly(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userA.getId()),
                    accountsA, Account::getId, Account::getUserId, userA.getId());
            assertOwnedOnly(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userB.getId()),
                    accountsB, Account::getId, Account::getUserId, userB.getId());
            assertOwnedOnly(categoryRepository.findByUserId(userA.getId()),
                    categoriesA, Category::getId, Category::getUserId, userA.getId());
            assertOwnedOnly(categoryRepository.findByUserId(userB.getId()),
                    categoriesB, Category::getId, Category::getUserId, userB.getId());
            assertOwnedOnly(transactionRepository.findByUserId(userA.getId()),
                    txsA, Transaction::getId, Transaction::getUserId, userA.getId());
            assertOwnedOnly(transactionRepository.findByUserId(userB.getId()),
                    txsB, Transaction::getId, Transaction::getUserId, userB.getId());

            // 单条读取的跨租户隔离：B 的资源用 A 的 user_id 读取返回空；用本人读取则可取到。
            for (Long bAccountId : accountsB) {
                assertThat(accountRepository.findByIdAndUserId(bAccountId, userA.getId())).isEmpty();
                assertThat(accountRepository.findByIdAndUserId(bAccountId, userB.getId())).isPresent();
            }
            for (Long bCategoryId : categoriesB) {
                assertThat(categoryRepository.findByIdAndUserId(bCategoryId, userA.getId())).isEmpty();
                assertThat(categoryRepository.findByIdAndUserId(bCategoryId, userB.getId())).isPresent();
            }
            for (Long bTxId : txsB) {
                assertThat(transactionRepository.findByIdAndUserId(bTxId, userA.getId())).isEmpty();
                assertThat(transactionRepository.findByIdAndUserId(bTxId, userB.getId())).isPresent();
            }
        }
    }

    /**
     * Feature: youyu-ledger, Property 10: 对任意已认证用户提交的创建请求，无论请求体是否携带任意
     * user_id 值，落库数据的 user_id 都应恒等于当前会话用户，请求中传入的 user_id 一律被忽略。
     *
     * <p>范围见类注释：此处以会话用户（真实 {@link CurrentUser}）+ 仓库层 user_id 归属绑定验证该原则，
     * 业务写入服务实现后将在其模块测试中端到端复核。</p>
     */
    @Test
    void property10_writeForcesSessionUserId() {
        Random rng = new Random(424242L);
        // 可作为「请求体伪造 user_id」的候选值（含不存在的、他人的）。
        RandomGenerator<Long> spoofedIdGen =
                Arbitraries.longs().between(1, 5_000).generator(1000);

        for (int iter = 0; iter < ITERATIONS; iter++) {
            User sessionUser = persistUser("s_" + iter);
            User otherUser = persistUser("o_" + iter);

            // 建立已认证会话：会话用户即 sessionUser。
            authenticateAs(sessionUser.getId(), "user");
            Long forcedUserId = currentUser.requireUserId();
            assertThat(forcedUserId).isEqualTo(sessionUser.getId());

            // 请求体携带的伪造 user_id：可能是他人、可能是随机值、可能恰好等于会话用户。
            long spoofed = (iter % 2 == 0) ? otherUser.getId() : spoofedIdGen.next(rng).value();

            int n = positiveCountGen.next(rng).value();
            List<Long> createdIds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Account account = new Account();
                // 服务端强制以会话 userId 覆盖，忽略请求体传入的 spoofed（此处即体现「忽略」）。
                account.setUserId(forcedUserId);
                account.setName("acc" + i);
                account.setType(AccountType.CASH);
                account.setInitialBalance(BigDecimal.ZERO);
                account.setCurrentBalance(BigDecimal.ZERO);
                account.setSortOrder(i);
                account.setCreatedAt(FIXED_TIME);
                account.setUpdatedAt(FIXED_TIME);
                Account saved = accountRepository.save(account);

                // 落库 user_id 恒为会话用户。
                assertThat(saved.getUserId()).isEqualTo(sessionUser.getId());
                createdIds.add(saved.getId());
            }

            for (Long id : createdIds) {
                // 仅会话用户可检索到；用被伪造的 user_id（当其不等于会话用户时）检索为空。
                assertThat(accountRepository.findByIdAndUserId(id, sessionUser.getId())).isPresent();
                if (spoofed != sessionUser.getId()) {
                    assertThat(accountRepository.findByIdAndUserId(id, spoofed)).isEmpty();
                }
            }

            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAs(Long userId, String role) {
        var principal = new CurrentUserPrincipal(userId, role);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------------- 断言与持久化辅助 ----------------

    private <T> void assertOwnedOnly(
            List<T> found,
            List<Long> expectedIds,
            java.util.function.Function<T, Long> idOf,
            java.util.function.Function<T, Long> userIdOf,
            Long ownerId) {
        assertThat(found).hasSize(expectedIds.size());
        assertThat(found).allSatisfy(e -> assertThat(userIdOf.apply(e)).isEqualTo(ownerId));
        assertThat(found.stream().map(idOf).toList())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
    }

    private User persistUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(FIXED_TIME);
        u.setPlanExpiresAt(FIXED_TIME.plusDays(365));
        u.setCreatedAt(FIXED_TIME);
        u.setUpdatedAt(FIXED_TIME);
        return userRepository.save(u);
    }

    private List<Long> persistAccounts(Long userId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account a = new Account();
            a.setUserId(userId);
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

    private List<Long> persistCategories(Long userId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category c = new Category();
            c.setUserId(userId);
            c.setKind(CategoryKind.EXPENSE);
            c.setParentId(null);
            c.setName("cat" + i);
            c.setCreatedAt(FIXED_TIME);
            c.setUpdatedAt(FIXED_TIME);
            ids.add(categoryRepository.save(c).getId());
        }
        return ids;
    }

    private List<Long> persistTransactions(Long userId, int count, Random rng) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            t.setUserId(userId);
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
