package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 分页参数拒绝边界的属性测试（Property 10）：{@code page} / {@code size} 的合法域之外一律以
 * {@code INVITE_PAGE_PARAM_INVALID} 拒绝，且拒绝时不产生任何列表项与任何计数值。
 *
 * <h2>测试层级选择</h2>
 * <p>被测的全部语义（解析、边界、{@code field} 归属、拒绝时不查库）都在
 * {@link InviteService#listInvitees} 内完成，与数据库无关，故走单元层：两个仓库为 Mockito 测试替身，
 * 「响应体不含任何列表项与任何计数值」由
 * {@link Mockito#verifyNoInteractions(Object...)} 断言——拒绝路径连计数查询都不该发出，
 * 更无从组装 {@code items} / {@code total} / {@code invitedCount}。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>测试不复用被测的解析代码：合法性由「去空白后是否为 {@code [+-]?[0-9]{1,18}} 且落在闭区间内」
 * 独立判定（缺失 / 空白视为取缺省值，因而合法）。这样超出 {@code int} 范围的数字串
 * （被测实现走「不可解析」分支）与普通越界数字串（走「越界」分支）都被要求收敛到同一个错误码，
 * 正是需求 7.9 把两者并列的本意。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>原文入参：缺失 / {@code null} / 空白 ∪ 边界集 {-1, 0, 1, 20, 50, 51, 100000, 100001,
 *       int 边界, 超大数} ∪ 任意 {@code int} ∪ 带首尾空白的合法值 ∪ 任意非数字串。</li>
 *   <li>整数入参：{@code null} ∪ 边界集 ∪ 任意 {@code int}——覆盖控制器已完成类型转换的调用路径。</li>
 *   <li>{@code page} 与 {@code size} 独立生成，因而覆盖「两者同时非法」的组合（{@code field} 取 page）。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 10: 分页参数的拒绝边界</p>
 *
 * <p>Validates: Requirements 7.9</p>
 */
class InvitePageParamPropertyTest {

    private static final long USER_ID = 42L;

    /** 期望值一侧的严格十进制整数形状；刻意不调用被测实现的解析逻辑。 */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]{1,18}");

    // ---------------- 生成器 ----------------

    /** 数值边界集：合法域内外各取临界值，外加 {@code int} 边界与超出 {@code long} 的数字串。 */
    private static final List<String> BOUNDARY_TEXTS = List.of(
            "-1", "0", "1", "19", "20", "21", "49", "50", "51",
            "99999", "100000", "100001", "-100001",
            "2147483647", "2147483648", "-2147483648", "-2147483649",
            "99999999999999999999", "007", "+5", "-0");

    /** 非数字串：空白、符号、小数、科学计数、十六进制、含内嵌空白等，一律不可解析。 */
    private static final List<String> NON_NUMERIC_TEXTS = List.of(
            "abc", "1.5", "1e3", "0x10", "--1", "+", "-", "5 5", "1,000",
            "20L", "null", "NaN", "Infinity", "1_0", "#20", "２０");

    /**
     * {@code page} / {@code size} 的原文输入空间：缺失（{@code null}）∪ 空白 ∪ 边界集 ∪
     * 任意 {@code int} ∪ 带首尾空白的合法值 ∪ 任意非数字串。
     */
    @Provide
    Arbitrary<String> rawParams() {
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t");
        Arbitrary<String> boundaries = Arbitraries.of(BOUNDARY_TEXTS);
        Arbitrary<String> anyInts = Arbitraries.integers().map(String::valueOf);
        Arbitrary<String> paddedInRange = Arbitraries.integers().between(0, MAX_OF_BOTH)
                .map(v -> "  " + v + " ");
        Arbitrary<String> nonNumeric = Arbitraries.oneOf(
                Arbitraries.of(NON_NUMERIC_TEXTS),
                Arbitraries.strings().withChars('a', 'z').withChars('A', 'Z')
                        .withChars(' ', '-', '+', '.', '/', ':').ofMinLength(1).ofMaxLength(6));
        return Arbitraries.oneOf(blanks, boundaries, anyInts, paddedInRange, nonNumeric)
                .injectNull(0.15);
    }

    /** 生成 padded 合法值时用的上界：覆盖 size 合法域（1–50）与 page 合法域（0–100000）两侧。 */
    private static final int MAX_OF_BOTH = InviteService.MAX_PAGE;

    /** 整数输入空间：{@code null}（取缺省）∪ 边界集 ∪ 任意 {@code int}。 */
    @Provide
    Arbitrary<Integer> intParams() {
        Arbitrary<Integer> boundaries = Arbitraries.of(
                -1, 0, 1, 19, 20, 21, 49, 50, 51, 99999,
                InviteService.MAX_PAGE, InviteService.MAX_PAGE + 1,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        return Arbitraries.oneOf(boundaries, Arbitraries.integers()).injectNull(0.15);
    }

    // ---------------- Property 10 ----------------

    /**
     * Feature: invite-system, Property 10: 分页参数的拒绝边界
     *
     * <p>对任意 {@code page} / {@code size} 输入（原文串版与整数版两条调用路径）：</p>
     * <ul>
     *   <li>落在合法域内（含缺失取缺省）→ 正常返回视图，生效的 {@code page} / {@code size}
     *       等于独立算出的期望值；</li>
     *   <li>不可解析为整数或越界（{@code page < 0}、{@code page > 100000}、{@code size < 1}、
     *       {@code size > 50}、非数字串、空串以外的非法串、超大数）→ 抛
     *       {@code INVITE_PAGE_PARAM_INVALID}（400），{@code field} 为 {@code page} 或 {@code size}
     *       （两者同时非法时取 {@code page}），且两个仓库零交互——因此不存在任何列表项与任何计数值。</li>
     * </ul>
     *
     * <p>Validates: Requirements 7.9</p>
     */
    @Property(tries = 200)
    void property10_pageParamRejectionBoundary(
            @ForAll("rawParams") String rawPage,
            @ForAll("rawParams") String rawSize,
            @ForAll("intParams") Integer intPage,
            @ForAll("intParams") Integer intSize) {

        // ---- 原文串版调用路径 ----
        Fixture rawFixture = new Fixture();
        String expectedRawField = rejectedField(rawPage, rawSize);
        if (expectedRawField != null) {
            assertRejected(() -> rawFixture.service.listInvitees(USER_ID, rawPage, rawSize),
                    expectedRawField, rawFixture);
        } else {
            InviteeListView view = rawFixture.service.listInvitees(USER_ID, rawPage, rawSize);
            assertAccepted(view, rawFixture,
                    effective(rawPage, InviteService.DEFAULT_PAGE),
                    effective(rawSize, InviteService.DEFAULT_SIZE));
        }

        // ---- 整数版调用路径 ----
        Fixture intFixture = new Fixture();
        String expectedIntField = rejectedField(text(intPage), text(intSize));
        if (expectedIntField != null) {
            assertRejected(() -> intFixture.service.listInvitees(USER_ID, intPage, intSize),
                    expectedIntField, intFixture);
        } else {
            InviteeListView view = intFixture.service.listInvitees(USER_ID, intPage, intSize);
            assertAccepted(view, intFixture,
                    intPage == null ? InviteService.DEFAULT_PAGE : intPage,
                    intSize == null ? InviteService.DEFAULT_SIZE : intSize);
        }
    }

    // ---------------- 断言 ----------------

    /** 拒绝路径：错误码 / 状态 / field 正确，且仓库零交互（无列表项、无计数值）。 */
    private static void assertRejected(Runnable call, String expectedField, Fixture fixture) {
        ApiException thrown = catchThrowableOfType(call::run, ApiException.class);
        assertThat(thrown).as("非法分页参数必须被拒绝").isNotNull();
        assertThat(thrown.getCode()).isEqualTo("INVITE_PAGE_PARAM_INVALID");
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.getField()).isEqualTo(expectedField);
        assertThat(thrown.getField()).isIn("page", "size");
        // 拒绝时既不分页查询也不计数：响应体无从包含 items / total / invitedCount。
        Mockito.verifyNoInteractions(fixture.inviteRelationRepository, fixture.userRepository);
    }

    /** 接受路径：正常返回视图，且生效的分页参数等于期望值。 */
    private static void assertAccepted(InviteeListView view, Fixture fixture,
            int expectedPage, int expectedSize) {
        assertThat(view).isNotNull();
        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.invitedCount()).isZero();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(fixture.inviteRelationRepository)
                .findByInviterId(Mockito.eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(captor.getValue().getPageSize()).isEqualTo(expectedSize);
    }

    // ---------------- 期望值：独立于被测实现 ----------------

    /** 被拒绝的字段名，或 {@code null} 表示应当被接受；{@code page} 先于 {@code size}。 */
    private static String rejectedField(String rawPage, String rawSize) {
        if (!acceptable(rawPage, 0, InviteService.MAX_PAGE)) {
            return "page";
        }
        if (!acceptable(rawSize, 1, InviteService.MAX_SIZE)) {
            return "size";
        }
        return null;
    }

    /** 缺失 / 空白取缺省故合法；否则须是严格十进制整数且落在闭区间内。 */
    private static boolean acceptable(String raw, long min, long max) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String trimmed = raw.strip();
        if (!DECIMAL.matcher(trimmed).matches()) {
            return false;
        }
        long value = Long.parseLong(trimmed);
        return value >= min && value <= max;
    }

    /** 合法原文的生效取值：缺失 / 空白取缺省。 */
    private static int effective(String raw, int defaultValue) {
        return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.strip());
    }

    private static String text(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------- 测试替身 ----------------

    /** 每次调用一套全新替身：便于用「零交互」表达「不产生任何列表项与计数值」。 */
    private static final class Fixture {
        private final UserRepository userRepository = Mockito.mock(UserRepository.class);
        private final InviteRelationRepository inviteRelationRepository =
                Mockito.mock(InviteRelationRepository.class);
        private final InviteService service;

        private Fixture() {
            Page<InviteRelation> empty = new PageImpl<>(List.of());
            Mockito.when(inviteRelationRepository.findByInviterId(Mockito.anyLong(), Mockito.any()))
                    .thenReturn(empty);
            Mockito.when(inviteRelationRepository.countByInviterId(Mockito.anyLong()))
                    .thenReturn(0L);
            Mockito.when(inviteRelationRepository.countByInviterIdAndStatus(
                            Mockito.anyLong(), Mockito.any(InviteStatus.class)))
                    .thenReturn(0L);
            // 限流器与本属性无关（listInvitees 不经限流），给个替身占位即可。
            service = new InviteService(userRepository, inviteRelationRepository,
                    new InviteCodeGenerator(), Mockito.mock(InviteRateLimiter.class),
                    Clock.fixed(Instant.parse("2025-06-01T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        }
    }
}
