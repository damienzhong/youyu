package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.CategoryResponse;
import com.damien.youyu.domain.Category;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 分类接口图标/配色契约测试（关联需求 5.2、5.3、5.4、6.2、6.3；设计 Property 2、3、5）。
 *
 * <p>沿用 {@link CategoryServiceTest} 的切片基建（H2 + 真实 {@link CategoryRepository}/
 * {@link TransactionRepository}、固定 {@link Clock}，不使用任何桩），走「服务层净化落库 →
 * {@link CategoryResponse#from} 回显」的完整路径，覆盖分类接口对 {@code icon}/{@code iconColor}
 * 的净化与回显语义：</p>
 * <ul>
 *   <li>合法 {@code icon}（白名单内）+ 合法 {@code iconColor}（{@code #RRGGBB}）→ 持久化并原样回显。</li>
 *   <li>非法 {@code icon} → 被净化（不原样落库），由名称推断兜底，不报错、不新增错误码（需求 5.2）。</li>
 *   <li>非法 {@code iconColor} → 净化为 {@code null}（默认色兜底），不报错（需求 5.3）。</li>
 *   <li>不传 {@code iconColor} → 回显 {@code null}（旧数据兼容语义，需求 6.2、5.4）。</li>
 *   <li>分类响应字段集合仅新增 {@code iconColor}，其余既有字段不变（需求 6.3）。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryIconContractTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;

    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private CategoryService service() {
        return new CategoryService(categoryRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- 创建：合法 icon + 合法 iconColor 持久化并回显 ----------------

    @Test
    void create_withValidIconAndColor_persistsAndEchoes() {
        Category created = service().create(USER, "EXPENSE", "咖啡", null, "coffee", "#e5563d");

        // 落库校验：从仓库重新读取，确认 icon/iconColor 已持久化。
        Category reloaded = categoryRepository.findByIdAndLedgerId(created.getId(), USER).orElseThrow();
        assertThat(reloaded.getIcon()).isEqualTo("coffee");
        assertThat(reloaded.getIconColor()).isEqualTo("#e5563d");

        // 契约回显：响应体原样返回白名单内 icon 与合法 iconColor。
        CategoryResponse resp = CategoryResponse.from(reloaded);
        assertThat(resp.icon()).isEqualTo("coffee");
        assertThat(resp.iconColor()).isEqualTo("#e5563d");
    }

    // ---------------- 创建：非法 icon 被净化（需求 5.2） ----------------

    @Test
    void create_withInvalidIcon_sanitizedAndGuessedByName_noError() {
        Category created = service().create(USER, "EXPENSE", "餐饮", null, "zzz", "#e5563d");

        CategoryResponse resp = CategoryResponse.from(created);
        // 非法 icon 不被原样落库/回显。
        assertThat(resp.icon()).isNotEqualTo("zzz");
        // 由名称推断兜底：命中白名单内的图标 key（"餐饮" → food）。
        assertThat(resp.icon()).isEqualTo("food");
        assertThat(CategoryIcons.KEYS).contains(resp.icon());
    }

    @Test
    void create_withInvalidIcon_doesNotThrow_noNewErrorCode() {
        // 需求 5.2/5.4：非法 icon 视为未提供，绝不报错、不新增错误码。
        assertThatCode(() -> service().create(USER, "EXPENSE", "交通", null, "not-a-key", null))
                .doesNotThrowAnyException();
    }

    // ---------------- 创建：非法 iconColor 净化为 null（需求 5.3） ----------------

    @Test
    void create_withInvalidColor_namedColor_returnsNull() {
        Category created = service().create(USER, "EXPENSE", "打车", null, "taxi", "red");

        CategoryResponse resp = CategoryResponse.from(created);
        assertThat(resp.iconColor()).isNull();
        // icon 合法照常回显，非法颜色不影响其它字段。
        assertThat(resp.icon()).isEqualTo("taxi");
    }

    @Test
    void create_withInvalidColor_shortHex_returnsNull() {
        Category created = service().create(USER, "EXPENSE", "地铁", null, "subway", "#12");

        assertThat(CategoryResponse.from(created).iconColor()).isNull();
    }

    @Test
    void create_withInvalidColor_doesNotThrow_noNewErrorCode() {
        assertThatCode(() -> service().create(USER, "EXPENSE", "购物", null, "shopping", "#GGGGGG"))
                .doesNotThrowAnyException();
    }

    // ---------------- 创建：不传 iconColor → null（旧数据兼容，需求 6.2、5.4） ----------------

    @Test
    void create_withoutColor_returnsNull() {
        Category created = service().create(USER, "EXPENSE", "早餐", null, "breakfast", null);

        CategoryResponse resp = CategoryResponse.from(created);
        assertThat(resp.iconColor()).isNull();
        assertThat(resp.icon()).isEqualTo("breakfast");
    }

    // ---------------- 更新：净化语义与回显 ----------------

    @Test
    void update_withValidIconAndColor_persistsAndEchoes() {
        Category created = service().create(USER, "EXPENSE", "娱乐", null, "movie", "#12a150");

        Category updated = service().update(USER, created.getId(), "娱乐", "game", "#5b8def");

        Category reloaded = categoryRepository.findByIdAndLedgerId(updated.getId(), USER).orElseThrow();
        CategoryResponse resp = CategoryResponse.from(reloaded);
        assertThat(resp.icon()).isEqualTo("game");
        assertThat(resp.iconColor()).isEqualTo("#5b8def");
    }

    @Test
    void update_withInvalidColor_sanitizedToNull_noError() {
        Category created = service().create(USER, "EXPENSE", "教育", null, "book", "#12a150");

        // 需求 5.3：提供非法颜色时净化为 null（默认色兜底），不报错。
        Category updated = service().update(USER, created.getId(), "教育", null, "not-a-color");

        assertThat(CategoryResponse.from(updated).iconColor()).isNull();
    }

    // ---------------- 契约：响应字段集合仅新增 iconColor（需求 6.3） ----------------

    @Test
    void categoryResponse_fieldSet_onlyAddsIconColor() {
        List<String> fields = Arrays.stream(CategoryResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // 既有字段保持不变，仅新增 iconColor（相对未含图标配色前的 id/kind/name/parentId/icon）。
        assertThat(fields)
                .containsExactly("id", "kind", "name", "parentId", "icon", "iconColor");
    }
}
