package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;

/**
 * {@link InviteService} 的示例与边界单元测试（关联需求 1.4、7.7、7.9、8.9）。
 *
 * <p>与两个属性测试的分工：{@code InviteStatsPagingPropertyTest}（Property 9）在 H2 上覆盖真实的
 * 排序与分页语义，{@code InvitePageParamPropertyTest}（Property 10）覆盖分页参数拒绝边界的全值域。
 * 本类只补它们刻意不覆盖的具体形状：惰性补齐的幂等与失败分支（属性测试用不到写路径）、
 * 空数据下的三个字段取值、需求原文点名的那组分页临界值、昵称四种缺失形态的填充，
 * 以及公开查询三种失败情形的<b>逐字段同构</b>。</p>
 *
 * <p>协作方一律用 Mockito 测试替身，{@link InviteCodeGenerator} 用<b>真实实例</b>：它无状态、
 * 无外部依赖，且「10 次候选码全被占用」这条分支正好靠 {@code existsByInviteCode} 恒真的替身触达，
 * 比 mock 掉生成器更贴近真实调用链。</p>
 */
@ExtendWith(MockitoExtension.class)
class InviteServiceEdgeCaseTest {

    private static final long USER_ID = 9001L;
    private static final String CODE = "K7M2Q9XT";
    private static final String IP = "203.0.113.7";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2025-06-01T04:00:00Z"), ZONE);
    /** 预置的审计时刻：用于断言「已非空时不做任何写入」连 {@code updated_at} 都不动。 */
    private static final LocalDateTime PRESET_AT = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

    @Mock
    private UserRepository userRepository;
    @Mock
    private InviteRelationRepository inviteRelationRepository;
    @Mock
    private InviteRateLimiter inviteRateLimiter;

    private InviteService service;

    @BeforeEach
    void setUp() {
        service = new InviteService(userRepository, inviteRelationRepository,
                new InviteCodeGenerator(), inviteRateLimiter, CLOCK);
    }

    // ================= 惰性补齐的幂等与失败分支（需求 1.4、1.8、1.13） =================

    /**
     * 已有非空邀请码时连续两次请求返回同一个码，且<b>零写入</b>：不抽候选码、不 {@code save}、
     * 连 {@code updated_at} 都保持原值（需求 1.4、1.13）。
     */
    @Test
    void existingCodeIsReturnedTwiceWithoutAnyWrite() {
        User user = user(CODE);
        when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.requireInviteCode(USER_ID)).isEqualTo(CODE);
        assertThat(service.requireInviteCode(USER_ID)).isEqualTo(CODE);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByInviteCode(anyString());
        assertThat(user.getInviteCode()).isEqualTo(CODE);
        assertThat(user.getUpdatedAt()).as("幂等请求不改审计列").isEqualTo(PRESET_AT);
    }

    /**
     * {@code invite_code} 为 {@code null} 或全空白时视为待补齐：补齐一次并持久化，
     * 补齐后的第二次请求直接返回同一个码、不再抽码也不再 {@code save}（需求 1.3、1.4）。
     */
    @Test
    void blankCodeIsFilledOnceThenStable() {
        for (String blank : new String[] { null, "", "   " }) {
            User user = user(blank);
            when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.existsByInviteCode(anyString())).thenReturn(false);

            String filled = service.requireInviteCode(USER_ID);

            assertThat(filled)
                    .as("补齐后的邀请码：8 位、字符全部取自字母表")
                    .hasSize(InviteCodeGenerator.LENGTH)
                    .matches("[" + InviteCodeGenerator.ALPHABET + "]{8}");
            assertThat(user.getInviteCode()).isEqualTo(filled);
            assertThat(user.getUpdatedAt()).isEqualTo(LocalDateTime.now(CLOCK));

            assertThat(service.requireInviteCode(USER_ID))
                    .as("补齐后再次请求返回同一个码")
                    .isEqualTo(filled);
            verify(userRepository, times(1)).save(user);

            org.mockito.Mockito.reset(userRepository);
        }
    }

    /**
     * 10 次候选码全被占用：抛 {@code INVITE_CODE_GEN_FAILED}、恰好尝试 10 次、不 {@code save}，
     * {@code invite_code} 保持原值 {@code null}（需求 1.8）。
     */
    @Test
    void codeGenerationFailureKeepsCodeNullAndWritesNothing() {
        User user = user(null);
        when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByInviteCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.requireInviteCode(USER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITE_CODE_GEN_FAILED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                });

        verify(userRepository, times(InviteCodeGenerator.MAX_ATTEMPTS)).existsByInviteCode(anyString());
        verify(userRepository, never()).save(any());
        assertThat(user.getInviteCode()).isNull();
    }

    /**
     * 补齐失败时 {@code getInviteInfo} 整体失败：不返回「部分填充」的视图，连已邀请人数都不查
     * （需求 1.8——响应不含三个字段中任何一个的值）。
     */
    @Test
    void inviteInfoFailsWholeWhenCodeGenerationFails() {
        when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.of(user(null)));
        when(userRepository.existsByInviteCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.getInviteInfo(USER_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVITE_CODE_GEN_FAILED"));

        verifyNoInteractions(inviteRelationRepository);
    }

    /** 令牌用户已不存在（注销后旧令牌仍在有效期内）：{@code UNAUTHENTICATED}，不抽码不写库。 */
    @Test
    void missingUserIsUnauthenticated() {
        when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireInviteCode(USER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("UNAUTHENTICATED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        verify(userRepository, never()).save(any());
    }

    // ================= 空数据：invitedCount 为 0（需求 1.10、7.10） =================

    /** 没有任何邀请关系时：已邀请人数为 0，邀请码与链接照常返回且链接不做额外转义（需求 1.10、2.1）。 */
    @Test
    void inviteInfoOnEmptyDataReturnsZeroCount() {
        when(userRepository.findForUpdateById(USER_ID)).thenReturn(Optional.of(user(CODE)));
        when(inviteRelationRepository.countByInviterIdAndStatus(USER_ID, InviteStatus.REGISTERED))
                .thenReturn(0L);

        InviteInfoView view = service.getInviteInfo(USER_ID);

        assertThat(view.inviteCode()).isEqualTo(CODE);
        assertThat(view.inviteLink()).isEqualTo("/pages/invitelanding/invitelanding?code=" + CODE);
        assertThat(view.invitedCount()).isZero();
    }

    /** 空列表：三个字段都是零值，且不发起任何昵称查询（当前页无被邀请人，无从批量补昵称）。 */
    @Test
    void listInviteesOnEmptyDataReturnsEmptyViewWithoutNicknameLookup() {
        stubRelations(List.of(), 0L, 0L);

        InviteeListView view = service.listInvitees(USER_ID, (Integer) null, null);

        assertThat(view.items()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.invitedCount()).isZero();
        verify(userRepository, never()).findAllById(any());
    }

    // ================= 分页参数的具体临界值（需求 7.9） =================

    /** 需求点名的合法临界值：{@code page} 取 0/1/100000、{@code size} 取 1/50，以及两者缺省。 */
    @Test
    void acceptedPageParamBoundaries() {
        stubRelations(List.of(), 0L, 0L);

        assertPageable(service.listInvitees(USER_ID, "0", "1"), 0, 1);
        assertPageable(service.listInvitees(USER_ID, "1", "50"), 1, 50);
        assertPageable(service.listInvitees(USER_ID, "100000", "20"), 100_000, 20);
        // 缺省：page 0、size 20（原文缺失与去空白为空同样按缺省处理）。
        assertPageable(service.listInvitees(USER_ID, (String) null, null),
                InviteService.DEFAULT_PAGE, InviteService.DEFAULT_SIZE);
        assertPageable(service.listInvitees(USER_ID, "  ", "\t"),
                InviteService.DEFAULT_PAGE, InviteService.DEFAULT_SIZE);
        assertPageable(service.listInvitees(USER_ID, (Integer) null, null),
                InviteService.DEFAULT_PAGE, InviteService.DEFAULT_SIZE);
    }

    /**
     * 需求点名的非法取值：{@code page} 为 -1/100001、{@code size} 为 0/51、以及非数字串
     * ——一律 {@code INVITE_PAGE_PARAM_INVALID}（400），{@code field} 指向出错的那个参数，
     * 且响应体不含任何列表项与任何计数值（两个仓库零交互）。
     */
    @Test
    void rejectedPageParamBoundaries() {
        assertRejected(() -> service.listInvitees(USER_ID, "-1", "20"), "page");
        assertRejected(() -> service.listInvitees(USER_ID, "100001", "20"), "page");
        assertRejected(() -> service.listInvitees(USER_ID, "0", "0"), "size");
        assertRejected(() -> service.listInvitees(USER_ID, "0", "51"), "size");
        assertRejected(() -> service.listInvitees(USER_ID, "abc", "20"), "page");
        assertRejected(() -> service.listInvitees(USER_ID, "0", "2.5"), "size");
        // 整数版调用路径（控制器已完成类型转换）同样拒绝。
        assertRejected(() -> service.listInvitees(USER_ID, -1, 20), "page");
        assertRejected(() -> service.listInvitees(USER_ID, InviteService.MAX_PAGE + 1, 20), "page");
        assertRejected(() -> service.listInvitees(USER_ID, 0, InviteService.MAX_SIZE + 1), "size");
    }

    /** 两者同时非法（size 不可解析 + page 越界）时 {@code field} 恒取 {@code page}，使错误响应确定。 */
    @Test
    void pageFailureOutranksSizeFailure() {
        assertRejected(() -> service.listInvitees(USER_ID, "100001", "abc"), "page");
        assertRejected(() -> service.listInvitees(USER_ID, "abc", "51"), "page");
    }

    // ================= 昵称填充（需求 7.7、10.8） =================

    /**
     * 四种「昵称不可用」形态——{@code null}、空串、全空白、被邀请人已注销（{@code users} 行不存在）
     * ——一律以 {@code null} 返回昵称，其余三个字段仍是真实取值，本次请求不失败、不用占位文本；
     * 昵称走一次 {@code findAllById} 批量查询（单页无 N+1）。
     */
    @Test
    void unavailableNicknamesBecomeNullWithoutFailingRequest() {
        List<InviteRelation> relations = List.of(
                relation(1L, 101L, InviteStatus.REGISTERED),
                relation(2L, 102L, InviteStatus.REGISTERED),
                relation(3L, 103L, InviteStatus.INVALID),
                relation(4L, 104L, InviteStatus.REGISTERED),
                relation(5L, 105L, InviteStatus.INVALID));
        stubRelations(relations, 5L, 3L);
        // 105 刻意不返回：被邀请人已注销，users 行不存在。
        when(userRepository.findAllById(any())).thenReturn(List.of(
                invitee(101L, "有余用户"),
                invitee(102L, null),
                invitee(103L, ""),
                invitee(104L, "   ")));

        InviteeListView view = service.listInvitees(USER_ID, 0, 20);

        assertThat(view.items()).hasSize(5);
        assertThat(view.items().get(0).nickname()).isEqualTo("有余用户");
        assertThat(view.items().subList(1, 5))
                .as("null / 空串 / 全空白 / 已注销一律以空值返回昵称")
                .allSatisfy(item -> assertThat(item.nickname()).isNull());

        // 其余三个字段始终是真实取值。
        for (int i = 0; i < relations.size(); i++) {
            InviteRelation source = relations.get(i);
            InviteeItemView item = view.items().get(i);
            assertThat(item.inviteId()).isEqualTo(source.getInviteId());
            assertThat(item.registerTime()).isEqualTo(source.getRegisterTime());
            assertThat(item.status()).isEqualTo(source.getStatus().name());
        }
        assertThat(view.total()).isEqualTo(5L);
        assertThat(view.invitedCount()).isEqualTo(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository, times(1)).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(101L, 102L, 103L, 104L, 105L);
    }

    /** 昵称查询整页全部缺失（例如一整页被邀请人都已注销）时仍正常返回，不抛错。 */
    @Test
    void allNicknamesMissingStillSucceeds() {
        stubRelations(List.of(relation(7L, 107L, InviteStatus.INVALID)), 1L, 0L);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        assertThatCode(() -> {
            InviteeListView view = service.listInvitees(USER_ID, 0, 20);
            assertThat(view.items()).singleElement()
                    .satisfies(item -> assertThat(item.nickname()).isNull());
            assertThat(view.total()).isEqualTo(1L);
            assertThat(view.invitedCount()).isZero();
        }).doesNotThrowAnyException();
    }

    // ================= 公开查询：三种失败同构（需求 8.9） =================

    /**
     * 格式非法（长度不为 8）、含字母表以外的字符、规整后合法但库中不存在——三者抛出的异常
     * 逐字段相同：同一 {@code NOT_FOUND}、同一 404、同一条文案、{@code field} 恒为 {@code null}，
     * 且文案不含入参原文、长度或失败细分原因（否则响应差异本身即可用于枚举邀请码）。
     */
    @Test
    void threeLookupFailuresAreIndistinguishable() {
        when(inviteRateLimiter.tryAcquireInviterLookup(IP)).thenReturn(true);
        when(userRepository.findByInviteCode("K7M2Q9XT")).thenReturn(Optional.empty());

        ApiException malformedLength = lookupFailure("ABC");
        ApiException illegalChars = lookupFailure("ABCDEFG0");   // '0' 不在字母表内，长度为 8
        ApiException notInDatabase = lookupFailure("k7m2q9xt");  // 规整后合法，但库中查不到

        for (ApiException ex : List.of(malformedLength, illegalChars, notInDatabase)) {
            assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getMessage()).isEqualTo(InviteService.INVITER_NOT_FOUND_MESSAGE);
            assertThat(ex.getField()).as("field 恒为 null").isNull();
        }
        // 三者两两同构：任何差异都是可用于区分「格式非法」与「不存在」的旁路信号。
        assertThat(signature(malformedLength)).isEqualTo(signature(illegalChars));
        assertThat(signature(illegalChars)).isEqualTo(signature(notInDatabase));
        // 文案不泄漏入参与失败细分原因。
        assertThat(malformedLength.getMessage())
                .doesNotContain("ABC", "3", "格式", "长度", "字符");
    }

    /** 规整（去空白 + 转大写）后命中：只返回昵称一个字段。 */
    @Test
    void lookupNormalizesRawCodeBeforeMatching() {
        when(inviteRateLimiter.tryAcquireInviterLookup(IP)).thenReturn(true);
        when(userRepository.findByInviteCode(CODE)).thenReturn(Optional.of(invitee(1L, "有余小明")));

        assertThat(service.findInviterNickname("  k7m2q9xt  ", IP)).isEqualTo("有余小明");
    }

    /** 邀请人昵称为 NULL / 空串 / 全空白时以空值返回该字段，不用占位文本（需求 4.2、8.5）。 */
    @Test
    void lookupReturnsNullForBlankInviterNickname() {
        when(inviteRateLimiter.tryAcquireInviterLookup(IP)).thenReturn(true);
        for (String blank : new String[] { null, "", "   " }) {
            when(userRepository.findByInviteCode(CODE)).thenReturn(Optional.of(invitee(1L, blank)));
            assertThat(service.findInviterNickname(CODE, IP)).isNull();
        }
    }

    /** 限流先于一切：被拒时 429，且完全不碰数据库（不规整、不校验、不查库，需求 8.6、8.7）。 */
    @Test
    void rateLimitPrecedesEverythingAndTouchesNoData() {
        when(inviteRateLimiter.tryAcquireInviterLookup(IP)).thenReturn(false);

        assertThatThrownBy(() -> service.findInviterNickname(CODE, IP))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INVITE_RATE_LIMITED");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });

        verifyNoInteractions(userRepository, inviteRelationRepository);
    }

    /** {@code null} 与空白入参同样走「查不到」这一条出口，不抛别的异常（需求 1.9）。 */
    @Test
    void nullAndBlankRawCodeFallIntoTheSameFailure() {
        when(inviteRateLimiter.tryAcquireInviterLookup(IP)).thenReturn(true);

        assertThat(signature(lookupFailure(null)))
                .isEqualTo(signature(lookupFailure("   ")));
        verify(userRepository, never()).findByInviteCode(anyString());
    }

    // ================= 断言与替身工具 =================

    private ApiException lookupFailure(String rawCode) {
        ApiException thrown = catchThrowableOfType(
                () -> service.findInviterNickname(rawCode, IP), ApiException.class);
        assertThat(thrown).as("入参 %s 应被拒绝", rawCode).isNotNull();
        return thrown;
    }

    /** 错误响应的可观测签名：{@code {code, status, message, field}}。 */
    private static List<Object> signature(ApiException ex) {
        return List.of(ex.getCode(), ex.getStatus(), ex.getMessage(),
                String.valueOf(ex.getField()));
    }

    private void assertPageable(InviteeListView view, int expectedPage, int expectedSize) {
        assertThat(view).isNotNull();
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(inviteRelationRepository, org.mockito.Mockito.atLeastOnce())
                .findByInviterId(eq(USER_ID), captor.capture());
        Pageable last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getPageNumber()).isEqualTo(expectedPage);
        assertThat(last.getPageSize()).isEqualTo(expectedSize);
    }

    private void assertRejected(Runnable call, String expectedField) {
        ApiException thrown = catchThrowableOfType(call::run, ApiException.class);
        assertThat(thrown).as("非法分页参数必须被拒绝").isNotNull();
        assertThat(thrown.getCode()).isEqualTo("INVITE_PAGE_PARAM_INVALID");
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.getField()).isEqualTo(expectedField);
        // 拒绝时不查库：响应体无从包含任何列表项与任何计数值。
        verifyNoInteractions(inviteRelationRepository, userRepository);
    }

    private void stubRelations(List<InviteRelation> content, long total, long registered) {
        Page<InviteRelation> page = new PageImpl<>(new ArrayList<>(content));
        when(inviteRelationRepository.findByInviterId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(page);
        when(inviteRelationRepository.countByInviterId(USER_ID)).thenReturn(total);
        when(inviteRelationRepository.countByInviterIdAndStatus(anyLong(), any(InviteStatus.class)))
                .thenReturn(registered);
    }

    private static User user(String inviteCode) {
        User u = new User();
        u.setId(USER_ID);
        u.setNickname("邀请人");
        u.setInviteCode(inviteCode);
        u.setCreatedAt(PRESET_AT);
        u.setUpdatedAt(PRESET_AT);
        return u;
    }

    private static User invitee(long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }

    private static InviteRelation relation(long inviteId, long inviteeId, InviteStatus status) {
        InviteRelation r = new InviteRelation();
        r.setInviteId(inviteId);
        r.setInviterId(USER_ID);
        r.setInviteeId(inviteeId);
        r.setRegisterTime(LocalDateTime.of(2025, 5, 20, 10, 0).plusMinutes(inviteId));
        r.setStatus(status);
        r.setCreatedAt(PRESET_AT);
        r.setUpdatedAt(PRESET_AT);
        return r;
    }
}
