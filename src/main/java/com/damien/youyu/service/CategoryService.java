package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 分类服务：两级分类的创建、列表、重命名与删除（关联需求 5.1-5.9）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>创建（需求 5.1、5.2、5.7、5.8）：名称去空白后 1-50，否则 {@code CATEGORY_NAME_INVALID}；
 *       同一 kind、同一父级范围内不得重名，否则 {@code CATEGORY_NAME_DUPLICATE}。父分类
 *       {@code parentId} 为 null；子分类的 {@code parentId} 指向已存在的父分类。</li>
 *   <li>层级最多两级（需求 5.3）：当 {@code parentId} 指向的分类本身已有父级时拒绝
 *       {@code CATEGORY_DEPTH_EXCEEDED}，不创建任何分类。子分类的 kind 以父分类为准，保证父子一致。</li>
 *   <li>支出与收入分类各自独立（需求 5.6）：重名与树结构均在同一 kind 内比较。</li>
 *   <li>重命名（需求 5.4）：仅改名称，保持 kind、parentId 与其下所有 Transaction 关联不变；
 *       新名称同样受长度与范围内重名校验。</li>
 *   <li>删除（需求 5.5、5.9）：被至少一笔交易引用则拒绝 {@code CATEGORY_IN_USE}；仍含子分类则拒绝
 *       {@code CATEGORY_HAS_CHILDREN}；两者皆无方可删除。</li>
 * </ul>
 *
 * <p>所有操作均按会话 {@code ledgerId} 隔离：写入以传入 ledgerId 为准，读取/修改/删除他人分类
 * 一律返回 {@code NOT_FOUND}（需求 2.3、2.4）。</p>
 */
@Service
public class CategoryService {

    /** 分类名称去空白后允许的长度区间（需求 5.1、5.7）。 */
    static final int NAME_MIN = 1;
    static final int NAME_MAX = 50;

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    public CategoryService(
            CategoryRepository categoryRepository,
            TransactionRepository transactionRepository,
            Clock clock) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    /**
     * 创建父分类或子分类。
     *
     * @param ledgerId    会话用户（需求 2.2 强制覆盖 user_id）
     * @param rawKind   种类字符串（EXPENSE/INCOME）；提供 parentId 时以父分类的 kind 为准
     * @param rawName   分类名称（去空白后 1-50）
     * @param parentId  父分类 id；为 null 表示创建父分类
     * @throws ApiException CATEGORY_NAME_INVALID（名称非法，需求 5.7）；
     *                      NOT_FOUND（父分类不存在或不属于当前用户，需求 2.4）；
     *                      CATEGORY_DEPTH_EXCEEDED（父分类已是子分类，需求 5.3）；
     *                      CATEGORY_NAME_DUPLICATE（范围内重名，需求 5.8）
     */
    @Transactional
    public Category create(Long ledgerId, String rawKind, String rawName, Long parentId) {
        return create(ledgerId, rawKind, rawName, parentId, null);
    }

    /**
     * 创建父/子分类，并指定图标 key（为空时按名称启发式推断）。
     *
     * @param rawIcon 图标 key（内置图标集）；null/空时按名称推断
     */
    @Transactional
    public Category create(Long ledgerId, String rawKind, String rawName, Long parentId, String rawIcon) {
        String name = validateName(rawName);

        CategoryKind kind;
        if (parentId == null) {
            // 创建父分类：kind 取自请求。
            kind = validateKind(rawKind);
            // 需求 5.8：父级范围内(parent_id 为 NULL)重名校验（应用层补充，NULL 不参与唯一约束）。
            if (categoryRepository.existsByLedgerIdAndKindAndParentIdIsNullAndName(ledgerId, kind, name)) {
                throw ApiException.categoryNameDuplicate();
            }
        } else {
            // 创建子分类：父分类须存在且属于当前用户。
            Category parent = categoryRepository.findByIdAndLedgerId(parentId, ledgerId)
                    .orElseThrow(() -> ApiException.notFound("父分类不存在"));
            // 需求 5.3：层级最多两级——父分类本身已有父级则拒绝。
            if (parent.getParentId() != null) {
                throw ApiException.categoryDepthExceeded();
            }
            // 子分类的 kind 以父分类为准，保证父子一致（需求 5.6 各 kind 独立）。
            kind = parent.getKind();
            // 需求 5.8：同一父分类下的子分类之间重名校验。
            if (categoryRepository.existsByLedgerIdAndKindAndParentIdAndName(ledgerId, kind, parentId, name)) {
                throw ApiException.categoryNameDuplicate();
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Category category = new Category();
        category.setLedgerId(ledgerId);
        category.setParentId(parentId);
        category.setKind(kind);
        category.setName(name);
        category.setIcon(normalizeIcon(rawIcon, name, kind));
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category);
    }

    /** 图标 key 规范化：非空取其修剪值，空时按名称启发式推断。 */
    private String normalizeIcon(String rawIcon, String name, CategoryKind kind) {
        if (rawIcon != null && !rawIcon.isBlank()) {
            return rawIcon.trim();
        }
        return CategoryIcons.guess(name, kind);
    }

    /** 列出本人全部分类（供按 kind 分组与层级构建，需求 5.6）。 */
    @Transactional(readOnly = true)
    public List<Category> list(Long ledgerId) {
        return categoryRepository.findByLedgerId(ledgerId);
    }

    /**
     * 若当前账本尚无任何分类，则预置一套默认收支分类（两级：父 + 子，见 {@link DefaultCategories}）；
     * 否则原样返回。幂等，供新手引导调用。
     */
    @Transactional
    public List<Category> seedDefaultsIfEmpty(Long ledgerId) {
        List<Category> existing = categoryRepository.findByLedgerId(ledgerId);
        if (!existing.isEmpty()) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        seedTree(ledgerId, CategoryKind.EXPENSE, DefaultCategories.EXPENSE, now);
        seedTree(ledgerId, CategoryKind.INCOME, DefaultCategories.INCOME, now);
        return categoryRepository.findByLedgerId(ledgerId);
    }

    /** 落库一组父分类及其子分类：先存父拿到 id，再挂子分类。 */
    private void seedTree(Long ledgerId, CategoryKind kind, DefaultCategories.Group[] groups, LocalDateTime now) {
        for (DefaultCategories.Group g : groups) {
            Category parent = categoryRepository.save(newCategory(ledgerId, kind, g.name(), null, now));
            for (String child : g.children()) {
                categoryRepository.save(newCategory(ledgerId, kind, child, parent.getId(), now));
            }
        }
    }

    private Category newCategory(Long ledgerId, CategoryKind kind, String name, Long parentId, LocalDateTime now) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setParentId(parentId);
        c.setKind(kind);
        c.setName(name);
        c.setIcon(CategoryIcons.guess(name, kind));
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /**
     * 重命名分类：仅改名称，保持 kind、parentId 与交易关联不变（需求 5.4）。
     *
     * @throws ApiException NOT_FOUND（分类不存在或不属于当前用户，需求 2.4）；
     *                      CATEGORY_NAME_INVALID（名称非法，需求 5.7）；
     *                      CATEGORY_NAME_DUPLICATE（范围内重名，需求 5.8）
     */
    @Transactional
    public Category rename(Long ledgerId, Long id, String rawName) {
        return update(ledgerId, id, rawName, null);
    }

    /**
     * 更新分类名称与图标：kind、parentId 与交易关联保持不变（需求 5.4）。
     *
     * @param rawIcon 新图标 key；null 表示不改动图标
     */
    @Transactional
    public Category update(Long ledgerId, Long id, String rawName, String rawIcon) {
        Category category = categoryRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));

        String name = validateName(rawName);

        // 名称实际变化时才做范围内重名校验（唯一性保证下，同名匹配即为其它分类）。
        if (!name.equals(category.getName())) {
            boolean duplicate = category.getParentId() == null
                    ? categoryRepository.existsByLedgerIdAndKindAndParentIdIsNullAndName(
                            ledgerId, category.getKind(), name)
                    : categoryRepository.existsByLedgerIdAndKindAndParentIdAndName(
                            ledgerId, category.getKind(), category.getParentId(), name);
            if (duplicate) {
                throw ApiException.categoryNameDuplicate();
            }
        }

        category.setName(name);
        if (rawIcon != null && !rawIcon.isBlank()) {
            category.setIcon(rawIcon.trim());
        }
        category.setUpdatedAt(LocalDateTime.now(clock));
        return categoryRepository.save(category);
    }

    /**
     * 删除分类：无交易引用且无子分类方可删除（需求 5.5、5.9）。
     *
     * @throws ApiException NOT_FOUND（分类不存在或不属于当前用户，需求 2.4）；
     *                      CATEGORY_IN_USE（被交易引用，需求 5.5）；
     *                      CATEGORY_HAS_CHILDREN（仍含子分类，需求 5.9）
     */
    @Transactional
    public void delete(Long ledgerId, Long id) {
        Category category = categoryRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));

        if (transactionRepository.existsByLedgerIdAndCategoryId(ledgerId, id)) {
            // 需求 5.5：被引用分类不可删除，分类及关联保持不变。
            throw ApiException.categoryInUse();
        }
        if (categoryRepository.existsByLedgerIdAndParentId(ledgerId, id)) {
            // 需求 5.9：含子分类不可删除。
            throw ApiException.categoryHasChildren();
        }
        categoryRepository.delete(category);
    }

    // ---------------- 校验 ----------------

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw ApiException.categoryNameInvalid();
        }
        return name;
    }

    private CategoryKind validateKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            throw ApiException.categoryNameInvalid();
        }
        try {
            return CategoryKind.valueOf(rawKind.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("CATEGORY_KIND_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的分类种类", "kind");
        }
    }
}
