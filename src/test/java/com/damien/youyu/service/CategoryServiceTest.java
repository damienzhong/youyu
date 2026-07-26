package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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
 * {@link CategoryService} 的示例与边界单元测试（关联需求 5.1-5.9）。
 *
 * <p>使用 H2 + 真实 {@link CategoryRepository}/{@link TransactionRepository}，不使用任何桩，
 * 以固定 {@link Clock} 做确定性时间。属性测试（Property 11-14）在任务 5.2 中实现。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private CategoryService service() {
        return new CategoryService(categoryRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- 创建：父/子（需求 5.1、5.2） ----------------

    @Test
    void create_parentCategory_hasNoParent() {
        Category parent = service().create(USER, "EXPENSE", "餐饮", null);

        assertThat(parent.getId()).isNotNull();
        assertThat(parent.getUserId()).isEqualTo(USER);
        assertThat(parent.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(parent.getName()).isEqualTo("餐饮");
        assertThat(parent.getParentId()).isNull();
    }

    @Test
    void create_childCategory_pointsToParent_andInheritsKind() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);

        // kind 传入与父分类不一致时以父分类为准（父子一致）。
        Category child = service.create(USER, "INCOME", "外卖", parent.getId());

        assertThat(child.getParentId()).isEqualTo(parent.getId());
        assertThat(child.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(child.getName()).isEqualTo("外卖");
    }

    @Test
    void create_trimsName() {
        Category parent = service().create(USER, "EXPENSE", "  餐饮  ", null);
        assertThat(parent.getName()).isEqualTo("餐饮");
    }

    // ---------------- 创建：层级限制（需求 5.3） ----------------

    @Test
    void create_thirdLevel_rejectedWithDepthExceeded_andNotPersisted() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);
        Category child = service.create(USER, "EXPENSE", "外卖", parent.getId());

        long before = categoryRepository.countByUserId(USER);
        ApiException ex = catchThrowableOfType(
                () -> service.create(USER, "EXPENSE", "午餐", child.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("CATEGORY_DEPTH_EXCEEDED");
        // 需求 5.3：不创建任何分类。
        assertThat(categoryRepository.countByUserId(USER)).isEqualTo(before);
    }

    @Test
    void create_childUnderMissingParent_returnsNotFound() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "EXPENSE", "外卖", 999L), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void create_childUnderOtherUsersParent_returnsNotFound() {
        Category parent = service().create(OTHER_USER, "EXPENSE", "餐饮", null);

        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "EXPENSE", "外卖", parent.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 创建：名称非法与重名（需求 5.7、5.8） ----------------

    @Test
    void create_invalidName_emptyOrTooLong_rejectedAndNotPersisted() {
        CategoryService service = service();

        ApiException empty = catchThrowableOfType(
                () -> service.create(USER, "EXPENSE", "   ", null), ApiException.class);
        assertThat(empty.getCode()).isEqualTo("CATEGORY_NAME_INVALID");

        ApiException tooLong = catchThrowableOfType(
                () -> service.create(USER, "EXPENSE", "n".repeat(51), null), ApiException.class);
        assertThat(tooLong.getCode()).isEqualTo("CATEGORY_NAME_INVALID");

        assertThat(categoryRepository.countByUserId(USER)).isZero();
    }

    @Test
    void create_duplicateParentName_sameKind_rejected() {
        CategoryService service = service();
        service.create(USER, "EXPENSE", "餐饮", null);

        ApiException ex = catchThrowableOfType(
                () -> service.create(USER, "EXPENSE", "餐饮", null), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_DUPLICATE");
        assertThat(categoryRepository.findByUserIdAndKind(USER, CategoryKind.EXPENSE)).hasSize(1);
    }

    @Test
    void create_sameName_differentKind_allowed() {
        // 需求 5.6：支出与收入分类各自独立，同名不冲突。
        CategoryService service = service();
        service.create(USER, "EXPENSE", "其他", null);
        Category income = service.create(USER, "INCOME", "其他", null);

        assertThat(income.getId()).isNotNull();
        assertThat(income.getKind()).isEqualTo(CategoryKind.INCOME);
    }

    @Test
    void create_duplicateChildName_underSameParent_rejected() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);
        service.create(USER, "EXPENSE", "外卖", parent.getId());

        ApiException ex = catchThrowableOfType(
                () -> service.create(USER, "EXPENSE", "外卖", parent.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_DUPLICATE");
    }

    @Test
    void create_sameChildName_underDifferentParents_allowed() {
        CategoryService service = service();
        Category food = service.create(USER, "EXPENSE", "餐饮", null);
        Category travel = service.create(USER, "EXPENSE", "出行", null);

        service.create(USER, "EXPENSE", "其他", food.getId());
        Category otherUnderTravel = service.create(USER, "EXPENSE", "其他", travel.getId());

        assertThat(otherUnderTravel.getId()).isNotNull();
    }

    @Test
    void create_invalidKind_rejected() {
        ApiException ex = catchThrowableOfType(
                () -> service().create(USER, "SAVINGS", "储蓄", null), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("CATEGORY_KIND_INVALID");
    }

    // ---------------- 列表（需求 5.6） ----------------

    @Test
    void list_returnsOnlyOwnCategories() {
        CategoryService service = service();
        service.create(USER, "EXPENSE", "餐饮", null);
        service.create(OTHER_USER, "EXPENSE", "别人的", null);

        List<Category> list = service.list(USER);
        assertThat(list).extracting(Category::getName).containsExactly("餐饮");
    }

    @Test
    void list_emptyWhenNoCategories() {
        assertThat(service().list(USER)).isEmpty();
    }

    // ---------------- 重命名（需求 5.4、5.7、5.8） ----------------

    @Test
    void rename_updatesName_preservesKindAndParentAndAssociations() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);
        Category child = service.create(USER, "EXPENSE", "外卖", parent.getId());
        // 关联一笔交易到子分类。
        persistExpenseWithCategory(USER, child.getId());

        Category renamed = service.rename(USER, child.getId(), "订餐");

        assertThat(renamed.getName()).isEqualTo("订餐");
        assertThat(renamed.getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(renamed.getParentId()).isEqualTo(parent.getId());
        // 需求 5.4：关联保持不变——交易仍引用该分类。
        assertThat(transactionRepository.existsByUserIdAndCategoryId(USER, child.getId())).isTrue();
    }

    @Test
    void rename_toSameName_isNoOpAllowed() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);

        Category renamed = service.rename(USER, parent.getId(), "餐饮");
        assertThat(renamed.getName()).isEqualTo("餐饮");
    }

    @Test
    void rename_toExistingNameInScope_rejected() {
        CategoryService service = service();
        service.create(USER, "EXPENSE", "餐饮", null);
        Category travel = service.create(USER, "EXPENSE", "出行", null);

        ApiException ex = catchThrowableOfType(
                () -> service.rename(USER, travel.getId(), "餐饮"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_DUPLICATE");
    }

    @Test
    void rename_invalidName_rejected() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);

        ApiException ex = catchThrowableOfType(
                () -> service.rename(USER, parent.getId(), "   "), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("CATEGORY_NAME_INVALID");
    }

    @Test
    void rename_otherUsersCategory_returnsNotFound() {
        Category parent = service().create(OTHER_USER, "EXPENSE", "餐饮", null);

        ApiException ex = catchThrowableOfType(
                () -> service().rename(USER, parent.getId(), "改名"), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    // ---------------- 删除（需求 5.5、5.9） ----------------

    @Test
    void delete_unusedLeafCategory_succeeds() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);

        service.delete(USER, parent.getId());

        assertThat(categoryRepository.findByIdAndUserId(parent.getId(), USER)).isEmpty();
    }

    @Test
    void delete_categoryReferencedByTransaction_rejectedWithInUse() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);
        persistExpenseWithCategory(USER, parent.getId());

        ApiException ex = catchThrowableOfType(
                () -> service.delete(USER, parent.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("CATEGORY_IN_USE");
        // 需求 5.5：分类保持不变。
        assertThat(categoryRepository.findByIdAndUserId(parent.getId(), USER)).isPresent();
    }

    @Test
    void delete_categoryWithChildren_rejectedWithHasChildren() {
        CategoryService service = service();
        Category parent = service.create(USER, "EXPENSE", "餐饮", null);
        service.create(USER, "EXPENSE", "外卖", parent.getId());

        ApiException ex = catchThrowableOfType(
                () -> service.delete(USER, parent.getId()), ApiException.class);

        assertThat(ex.getCode()).isEqualTo("CATEGORY_HAS_CHILDREN");
        assertThat(categoryRepository.findByIdAndUserId(parent.getId(), USER)).isPresent();
    }

    @Test
    void delete_otherUsersCategory_returnsNotFound() {
        Category parent = service().create(OTHER_USER, "EXPENSE", "餐饮", null);

        ApiException ex = catchThrowableOfType(
                () -> service().delete(USER, parent.getId()), ApiException.class);
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    private void persistExpenseWithCategory(Long userId, Long categoryId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setType(TransactionType.EXPENSE);
        tx.setAmount(new BigDecimal("12.00"));
        tx.setAccountId(1L);
        tx.setCategoryId(categoryId);
        tx.setOccurredAt(now);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);
    }
}
