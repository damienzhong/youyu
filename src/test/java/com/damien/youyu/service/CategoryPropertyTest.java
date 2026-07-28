package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link CategoryService} 的属性测试，覆盖设计文档 Correctness Properties 中的
 * Property 11-14（关联需求 5.3、5.4、5.5、5.7、5.8）：
 *
 * <ul>
 *   <li>Property 11：已构成「父分类 > 子分类」结构后，在子分类之下再创建下级分类的请求都应被拒绝
 *       （{@code CATEGORY_DEPTH_EXCEEDED}），不创建任何分类。</li>
 *   <li>Property 12：以任意满足约束的新名称重命名分类后，其名称更新为新名称，且该分类与其下所有
 *       Transaction 的关联集合保持不变（kind、parentId 亦不变）。</li>
 *   <li>Property 13：仍被至少一笔 Transaction 引用的分类不可删除（{@code CATEGORY_IN_USE}），
 *       该分类及其所有关联保持不变。</li>
 *   <li>Property 14：创建或重命名请求中名称去空白后为空或长度 > 50，或在同一 kind、同一父级范围内与
 *       已存在分类重名，则被拒绝（{@code CATEGORY_NAME_INVALID} 或 {@code CATEGORY_NAME_DUPLICATE}），
 *       不持久化任何变更。</li>
 * </ul>
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code AccountPropertyTest}、
 * {@code MultiTenantIsolationPropertyTest}）：在 {@code @DataJpaTest} + 真实 H2 与真实
 * {@link CategoryRepository}/{@link TransactionRepository} 上，以固定种子的 {@link Random} 在
 * {@code @Test} 循环内智能生成受约束的随机输入，驱动 ≥100 次迭代，被测的 {@link CategoryService}
 * 业务逻辑全部真实执行，不使用任何 mock。时间以固定 {@link Clock} 注入以获得确定性。（本类为
 * JUnit Jupiter 切片测试，随机输入用 {@link Random} 生成而非 jqwik {@code Arbitrary}，因为字符串/
 * 枚举 Arbitrary 需运行在 jqwik 线程内。）每次迭代使用独立 {@code ledgerId} 以隔离各次随机数据。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime FIXED_TIME = LocalDateTime.ofInstant(T0, ZONE);
    private static final int ITERATIONS = 120;

    private static final CategoryKind[] KINDS = CategoryKind.values();

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private CategoryService service() {
        return new CategoryService(categoryRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- 智能生成器（约束到输入空间的随机输入） ----------------

    /** 小写字母串，长度 minLen-maxLen。 */
    private static String letters(Random rng, int minLen, int maxLen) {
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }

    /** 合法名称：去空白后 1-50 个字符。 */
    private static String validName(Random rng) {
        return letters(rng, 1, 50);
    }

    /** 与 {@code exclude} 不同的合法名称。 */
    private static String validNameOtherThan(Random rng, String exclude) {
        String name;
        do {
            name = validName(rng);
        } while (name.equals(exclude));
        return name;
    }

    /** 受支持种类。 */
    private static CategoryKind validKind(Random rng) {
        return KINDS[rng.nextInt(KINDS.length)];
    }

    /** 非法名称：去空白后为空，或长度 > 50。 */
    private static String invalidName(Random rng) {
        if (rng.nextBoolean()) {
            // 全空白（含空串），去空白后为空。
            return " ".repeat(rng.nextInt(5));
        }
        // 超长（51-80）。
        return letters(rng, 51, 80);
    }

    // ---------------- 属性 ----------------

    /**
     * Feature: youyu-ledger, Property 11: 对任意已构成「父分类 > 子分类」结构的分类，在其子分类之下
     * 再创建下级分类的请求都应被拒绝，不创建任何分类，并返回层级最多两级的提示。
     */
    @Test
    void property11_thirdLevelRejectedWithZeroSideEffect() {
        Random rng = new Random(110011L);
        CategoryService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 11_000_000L + iter;
            CategoryKind kind = validKind(rng);

            Category parent = service.create(ledgerId, kind.name(), validName(rng), null);
            Category child = service.create(ledgerId, kind.name(), validName(rng), parent.getId());

            long before = categoryRepository.countByLedgerId(ledgerId);
            String thirdName = validName(rng);
            // kind 传入 EXPENSE/INCOME 两者皆试，子分类 kind 应以父为准，与深度校验无关。
            String requestKind = validKind(rng).name();

            ApiException ex = catchThrowableOfType(
                    () -> service.create(ledgerId, requestKind, thirdName, child.getId()),
                    ApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("CATEGORY_DEPTH_EXCEEDED");
            // 零副作用：分类数量不变。
            assertThat(categoryRepository.countByLedgerId(ledgerId)).isEqualTo(before);
        }
    }

    /**
     * Feature: youyu-ledger, Property 12: 对任意分类与任意满足约束的新名称，重命名该分类后，其名称应
     * 更新为新名称，且该分类与其下所有 Transaction 的关联集合保持不变。
     */
    @Test
    void property12_renamePreservesAssociations() {
        Random rng = new Random(120012L);
        CategoryService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 12_000_000L + iter;
            CategoryKind kind = validKind(rng);

            // 目标分类：随机为父分类或（挂在新建父分类下的）子分类。
            final Category target;
            if (rng.nextBoolean()) {
                target = service.create(ledgerId, kind.name(), validName(rng), null);
            } else {
                Category parent = service.create(ledgerId, kind.name(), validName(rng), null);
                target = service.create(ledgerId, kind.name(), validName(rng), parent.getId());
            }
            Long targetId = target.getId();
            CategoryKind kindBefore = target.getKind();
            Long parentIdBefore = target.getParentId();

            // 关联 0-4 笔交易到该分类，记录关联的交易 id 集合。
            int txCount = rng.nextInt(5);
            List<Long> associatedBefore = new ArrayList<>();
            for (int i = 0; i < txCount; i++) {
                associatedBefore.add(persistTransactionWithCategory(ledgerId, targetId, rng));
            }
            // 另加一笔引用其它分类 id 的交易，验证重命名不会误纳入无关关联。
            persistTransactionWithCategory(ledgerId, targetId + 987_654L, rng);

            String newName = validName(rng);
            Category renamed = service.rename(ledgerId, targetId, newName);

            // 名称更新为新名称。
            assertThat(renamed.getName()).isEqualTo(newName);
            // kind 与 parentId 保持不变。
            assertThat(renamed.getKind()).isEqualTo(kindBefore);
            assertThat(renamed.getParentId()).isEqualTo(parentIdBefore);

            // 关联集合保持不变：引用该分类的交易 id 集合与重命名前完全一致。
            List<Long> associatedAfter = transactionRepository.findByLedgerId(ledgerId).stream()
                    .filter(t -> targetId.equals(t.getCategoryId()))
                    .map(Transaction::getId)
                    .toList();
            assertThat(associatedAfter).containsExactlyInAnyOrderElementsOf(associatedBefore);
        }
    }

    /**
     * Feature: youyu-ledger, Property 13: 对任意仍被至少一笔 Transaction 引用的分类，删除请求应被拒绝，
     * 该分类及其所有关联保持不变，并返回该分类仍在使用的提示。
     */
    @Test
    void property13_referencedCategoryCannotBeDeleted() {
        Random rng = new Random(130013L);
        CategoryService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 13_000_000L + iter;
            CategoryKind kind = validKind(rng);

            Category category = service.create(ledgerId, kind.name(), validName(rng), null);
            Long categoryId = category.getId();
            String nameBefore = category.getName();

            // 至少一笔（1-5 笔）交易引用该分类。
            int txCount = 1 + rng.nextInt(5);
            List<Long> associatedBefore = new ArrayList<>();
            for (int i = 0; i < txCount; i++) {
                associatedBefore.add(persistTransactionWithCategory(ledgerId, categoryId, rng));
            }

            ApiException ex = catchThrowableOfType(
                    () -> service.delete(ledgerId, categoryId), ApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("CATEGORY_IN_USE");

            // 分类保持不变（仍存在、名称未变）。
            Category after = categoryRepository.findByIdAndLedgerId(categoryId, ledgerId).orElseThrow();
            assertThat(after.getName()).isEqualTo(nameBefore);
            assertThat(after.getKind()).isEqualTo(kind);
            // 关联保持不变：引用该分类的交易 id 集合未变。
            List<Long> associatedAfter = transactionRepository.findByLedgerId(ledgerId).stream()
                    .filter(t -> categoryId.equals(t.getCategoryId()))
                    .map(Transaction::getId)
                    .toList();
            assertThat(associatedAfter).containsExactlyInAnyOrderElementsOf(associatedBefore);
        }
    }

    /**
     * Feature: youyu-ledger, Property 14: 对任意分类创建或重命名请求，若名称去空白后为空或长度 > 50，
     * 或在同一 kind、同一父级范围内与已存在分类重名，则请求应被拒绝、不持久化任何变更，并返回相应错误。
     */
    @Test
    void property14_invalidNameOrDuplicateRejectedWithZeroSideEffect() {
        Random rng = new Random(140014L);
        CategoryService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 14_000_000L + iter;
            CategoryKind kind = validKind(rng);
            // 四种场景轮转：创建-非法名、重命名-非法名、创建-重名、重命名-重名。
            int mode = rng.nextInt(4);
            // 子级范围（挂在父分类下）与父级范围（parentId 为 null）各半，验证「同一父级范围」语义。
            boolean childScope = rng.nextBoolean();

            switch (mode) {
                case 0 -> { // 创建-非法名。
                    Long parentId = childScope
                            ? service.create(ledgerId, kind.name(), validName(rng), null).getId()
                            : null;
                    long before = categoryRepository.countByLedgerId(ledgerId);
                    String badName = invalidName(rng);

                    ApiException ex = catchThrowableOfType(
                            () -> service.create(ledgerId, kind.name(), badName, parentId),
                            ApiException.class);

                    assertThat(ex).isNotNull();
                    assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_INVALID");
                    assertThat(categoryRepository.countByLedgerId(ledgerId)).isEqualTo(before);
                }
                case 1 -> { // 重命名-非法名。
                    Long parentId = childScope
                            ? service.create(ledgerId, kind.name(), validName(rng), null).getId()
                            : null;
                    Category target = service.create(ledgerId, kind.name(), validName(rng), parentId);
                    String nameBefore = target.getName();
                    long before = categoryRepository.countByLedgerId(ledgerId);
                    String badName = invalidName(rng);

                    ApiException ex = catchThrowableOfType(
                            () -> service.rename(ledgerId, target.getId(), badName), ApiException.class);

                    assertThat(ex).isNotNull();
                    assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_INVALID");
                    // 零副作用：名称未变、数量未变。
                    Category after = categoryRepository.findByIdAndLedgerId(target.getId(), ledgerId)
                            .orElseThrow();
                    assertThat(after.getName()).isEqualTo(nameBefore);
                    assertThat(categoryRepository.countByLedgerId(ledgerId)).isEqualTo(before);
                }
                case 2 -> { // 创建-同范围重名。
                    Long parentId = childScope
                            ? service.create(ledgerId, kind.name(), validName(rng), null).getId()
                            : null;
                    String existingName = validName(rng);
                    service.create(ledgerId, kind.name(), existingName, parentId);
                    long before = categoryRepository.countByLedgerId(ledgerId);

                    ApiException ex = catchThrowableOfType(
                            () -> service.create(ledgerId, kind.name(), existingName, parentId),
                            ApiException.class);

                    assertThat(ex).isNotNull();
                    assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_DUPLICATE");
                    assertThat(categoryRepository.countByLedgerId(ledgerId)).isEqualTo(before);
                }
                default -> { // 重命名-同范围重名。
                    Long parentId = childScope
                            ? service.create(ledgerId, kind.name(), validName(rng), null).getId()
                            : null;
                    String nameA = validName(rng);
                    String nameB = validNameOtherThan(rng, nameA);
                    service.create(ledgerId, kind.name(), nameA, parentId);
                    Category target = service.create(ledgerId, kind.name(), nameB, parentId);
                    long before = categoryRepository.countByLedgerId(ledgerId);

                    ApiException ex = catchThrowableOfType(
                            () -> service.rename(ledgerId, target.getId(), nameA), ApiException.class);

                    assertThat(ex).isNotNull();
                    assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_DUPLICATE");
                    // 零副作用：目标名称仍为 nameB、数量未变。
                    Category after = categoryRepository.findByIdAndLedgerId(target.getId(), ledgerId)
                            .orElseThrow();
                    assertThat(after.getName()).isEqualTo(nameB);
                    assertThat(categoryRepository.countByLedgerId(ledgerId)).isEqualTo(before);
                }
            }
        }
    }

    // ---------------- 持久化辅助 ----------------

    /** 持久化一笔引用指定分类 id 的交易，返回其 id。 */
    private Long persistTransactionWithCategory(long ledgerId, Long categoryId, Random rng) {
        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(new BigDecimal(1 + rng.nextInt(9_999_999)).movePointLeft(2));
        tx.setAccountId(1L);
        tx.setCategoryId(categoryId);
        tx.setOccurredAt(FIXED_TIME);
        tx.setCreatedAt(FIXED_TIME);
        tx.setUpdatedAt(FIXED_TIME);
        return transactionRepository.save(tx).getId();
    }
}
