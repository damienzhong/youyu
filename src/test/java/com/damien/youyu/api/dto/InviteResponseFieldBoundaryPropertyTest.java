package com.damien.youyu.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.service.InviteCodeGenerator;
import com.damien.youyu.service.InviteInfoView;
import com.damien.youyu.service.InviteRateLimiter;
import com.damien.youyu.service.InviteService;
import com.damien.youyu.service.InviteeListView;
import com.damien.youyu.support.InMemoryUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 列表与展示信息的字段边界属性测试（Property 11）：邀请响应 DTO 的字段集恰为设计规定的那几项，
 * 且<strong>序列化后的 JSON 文本</strong>既不出现被排除字段的键、也不出现它们的取值。
 *
 * <h2>与 {@link InviteResponseDtoTest} 的分工</h2>
 * <p>那个例子测试断言的是 record 组件名（编译期结构）。本属性测试往前走一层：用生成的、逼真的
 * 用户数据（邮箱、openid、unionid、8 位邀请码）真实驱动 {@link InviteService} 的映射逻辑，
 * 再用<strong>项目的 Jackson 配置</strong>序列化 DTO，在 JSON 文本里搜这些取值。多一层的意义在于：
 * 字段名审查挡不住「通过 {@code @JsonAnyGetter}、父类字段、自定义序列化器或某天换成 Map 承载」
 * 而漏出去的取值——那些泄漏在 record 组件名上看不见，在 JSON 文本里看得见。</p>
 *
 * <h2>为什么按「值」搜而不只按「键」搜</h2>
 * <p>键名可以改（{@code email} → {@code contact}），值不会变。生成的取值都是高熵的
 * （邮箱本地部分 6 个随机小写字母、openid/unionid 27 个随机字符、邀请码 8 位），因此
 * 「JSON 里出现了这个字符串」几乎不可能是巧合，只能是泄漏。反过来，{@code plan} / {@code role}
 * 这类低熵枚举取值（{@code FREE} / {@code USER}）刻意只做键名断言：它们的字面量可能与
 * base64 或时间戳片段偶然相同，按值搜会引入假失败。</p>
 *
 * <h2>字段集断言用相等而非包含</h2>
 * <p>包含式断言（{@code contains}）只能证明该有的字段都在，证明不了不该有的字段都不在——而
 * 需求 7.4、8.5 的重点恰恰是后者。因此顶层键集与列表项键集一律用集合相等断言。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>昵称 ∈ {{@code null}、空串、纯空白、正常中文、64 字符、emoji}；</li>
 *   <li>被邀请人 ∈ {存在, 已注销（{@code users} 行不存在）}；</li>
 *   <li>关系状态 ∈ {{@code REGISTERED}, {@code INVALID}}，关系条数 0–12（含空列表）；</li>
 *   <li>邀请人与每个被邀请人各自持有随机邮箱 / openid / unionid / 邀请码。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 11: 列表与展示信息的字段边界</p>
 *
 * <p>Validates: Requirements 7.4, 7.7, 7.8, 8.3, 8.5, 10.8</p>
 */
class InviteResponseFieldBoundaryPropertyTest {

    /** 项目的 Jackson 配置（Spring Boot 自动配置 + {@code spring.jackson.time-zone}）。 */
    private static final ObjectMapper MAPPER = projectObjectMapper();

    /** 已注销被邀请人的 id 基数：刻意落在内存仓库自增序列之外，使 {@code users} 行查不到。 */
    private static final long DELETED_INVITEE_ID_BASE = 900_000L;

    /** 邀请关系主键基数，与用户 id 空间错开，便于断言失败时肉眼分辨。 */
    private static final long INVITE_ID_BASE = 5_000L;

    private static final String CLIENT_IP = "203.0.113.7";

    /**
     * 二维码 PNG 的 base64 文本，<strong>刻意是固定常量而不是生成值</strong>。
     *
     * <p>图片字节是不透明载荷，与本属性的输入空间无关（编码语义由 Property 13 覆盖）。若把它也随机
     * 生成，则「JSON 里不得出现某取值」这条断言会被 base64 字母表与邀请码字母表的重叠反噬：收缩会把
     * 邀请码压成 {@code AAAAAAAA}、把 base64 压成同一个字符的长串，于是必然「包含」——那是生成器
     * 的假失败，不是泄漏。固定常量既保住了断言的意义，也不损失覆盖。</p>
     */
    private static final String QRCODE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

    /**
     * 任何邀请响应 JSON 中都不得出现的键（camelCase 与 snake_case 两种写法都禁）。
     *
     * <p>三类：账号标识（{@code email} / {@code wx_openid} / {@code wx_unionid} /
     * {@code invite_code}，需求 7.8、8.5）、邀请人账号属性（{@code id} / {@code plan} /
     * {@code role}，需求 8.5）、以及任何「指定目标用户」的选择器（{@code userId} /
     * {@code inviterId} / {@code targetUserId} 之类，需求 8.3）。</p>
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "email",
            "wxOpenid", "wx_openid", "openid",
            "wxUnionid", "wx_unionid", "unionid",
            "inviteCode", "invite_code",
            "id", "plan", "role",
            "userId", "user_id",
            "inviterId", "inviter_id",
            "inviteeId", "invitee_id",
            "targetUserId", "target_user_id");

    /** {@code InviteInfoResponse} 是唯一允许出现 {@code inviteCode} 的 DTO：那是当前用户自己的码（需求 1.10）。 */
    private static final Set<String> FORBIDDEN_KEYS_EXCEPT_OWN_CODE = FORBIDDEN_KEYS.stream()
            .filter(key -> !"inviteCode".equals(key) && !"invite_code".equals(key))
            .collect(Collectors.toUnmodifiableSet());

    // ---------------- 生成器 ----------------

    /** 昵称输入空间：需求 7.7、10.8 点名的「取不到」形态（null / 空串 / 纯空白）+ 正常形态。 */
    private static Arbitrary<String> nicknames() {
        return Arbitraries.oneOf(
                        Arbitraries.of("", "   ", "\t\n"),
                        Arbitraries.of("小林同学", "阿常", "记账的猫", "\uD83D\uDC31\uD83C\uDF3F", "l\u00E9o"),
                        // 64 字符（列的长度上限）：用中文字符，避免与高熵取值的字母表重叠。
                        Arbitraries.of("很".repeat(64)))
                .injectNull(0.2);
    }

    /** 逼真邮箱：6 个随机小写字母 + 数字后缀 + 固定示例域名，熵足够高，绝不会在 JSON 里偶然出现。 */
    private static Arbitrary<String> emails() {
        return Combinators.combine(
                        Arbitraries.strings().withCharRange('a', 'z').ofLength(6),
                        Arbitraries.integers().between(100, 9999))
                .as((local, suffix) -> local + "." + suffix + "@example.com");
    }

    /** 逼真微信 openid / unionid：{@code o} + 27 个 base64url 字符，与真实取值同形。 */
    private static Arbitrary<String> wxIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('A', 'Z').withCharRange('0', '9')
                .withChars('_', '-')
                .ofLength(27)
                .map(tail -> "o" + tail);
    }

    /** 8 位邀请码：字符全部取自 {@link InviteCodeGenerator#ALPHABET}。 */
    private static Arbitrary<String> inviteCodes() {
        return Arbitraries.strings()
                .withChars(InviteCodeGenerator.ALPHABET.toCharArray())
                .ofLength(8);
    }

    private static Arbitrary<Secrets> secrets() {
        return Combinators.combine(emails(), wxIds(), wxIds(), inviteCodes(), nicknames())
                .as(Secrets::new);
    }

    private static Arbitrary<InviteeSpec> inviteeSpecs() {
        return Combinators.combine(
                        secrets(),
                        Arbitraries.of(true, false),
                        Arbitraries.of(InviteStatus.REGISTERED, InviteStatus.INVALID),
                        // register_time 取自小值域以制造并列，秒级精度避免纳秒往返噪声。
                        Arbitraries.integers().between(0, 6))
                .as(InviteeSpec::new);
    }

    /** 场景：一个邀请人 + 0–12 条邀请关系（含空列表、含已注销被邀请人）。 */
    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                        secrets(),
                        inviteeSpecs().list().ofMinSize(0).ofMaxSize(12))
                .as(Scenario::new);
    }

    // ---------------- Property 11 ----------------

    /**
     * Feature: invite-system, Property 11: 列表与展示信息的字段边界
     *
     * <p>对任意邀请关系集合与任意被邀请人状态（昵称正常 / NULL / 纯空白 / 已注销导致
     * {@code users} 行不存在）：</p>
     * <ul>
     *   <li>{@code InviteeListResponse} 顶层键集恰为 {@code {items, total, invitedCount}}，
     *       列表项键集恰为 {@code {inviteId, nickname, registerTime, status}}（需求 7.4）；</li>
     *   <li>昵称为 NULL / 空白 / 被邀请人不存在时一律为 JSON {@code null}，不用占位文本，
     *       其余三字段返回真实取值且请求成功（需求 7.7、10.8）；</li>
     *   <li>{@code InviteInfoResponse} 键集恰为 {@code {inviteCode, inviteLink, invitedCount}}，
     *       {@code InviterBriefResponse} 恰为 {@code {nickname}}（需求 8.5），
     *       {@code InviteQrCodeResponse} 恰为 {@code {imageBase64}}；</li>
     *   <li>四个 DTO 的 JSON 文本都不含被排除字段的键（{@code email} / {@code wx_openid} /
     *       {@code wx_unionid} / {@code invite_code} / {@code id} / {@code plan} / {@code role}
     *       与任何目标用户选择器），也不含它们的取值——邀请人自己的邀请码只允许出现在
     *       {@code InviteInfoResponse} 里（需求 7.8、8.3、8.5）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 7.4, 7.7, 7.8, 8.3, 8.5, 10.8</p>
     */
    @Property(tries = 25)
    void property11_listAndBriefFieldBoundary(@ForAll("scenarios") Scenario scenario)
            throws Exception {

        Fixture fixture = new Fixture(scenario);

        // ---- 被邀请人列表：字段集 + 昵称空值语义 + 其余三字段真实取值 ----
        InviteeListView listView = fixture.service.listInvitees(fixture.inviterId, 0, 50);
        InviteeListResponse listResponse = InviteeListResponse.from(listView);
        JsonNode listJson = assertFieldSet(listResponse,
                Set.of("items", "total", "invitedCount"), FORBIDDEN_KEYS);
        assertNoLeak(listResponse, fixture.allSecretValues());

        assertThat(listJson.get("total").asLong()).isEqualTo(fixture.relations.size());
        assertThat(listJson.get("invitedCount").asLong()).isEqualTo(fixture.registeredCount());

        JsonNode items = listJson.get("items");
        assertThat(items.size()).isEqualTo(fixture.relations.size());
        for (int i = 0; i < fixture.relations.size(); i++) {
            InviteRelation relation = fixture.relations.get(i);
            JsonNode item = items.get(i);

            assertThat(fieldNames(item))
                    .as("列表项字段集必须恰为四项（需求 7.4）")
                    .containsExactlyInAnyOrder("inviteId", "nickname", "registerTime", "status");

            // 昵称：取不到即 null，绝不用占位文本（需求 7.7、10.8）。
            String expectedNickname = fixture.expectedNickname(relation.getInviteeId());
            if (expectedNickname == null) {
                assertThat(item.get("nickname").isNull())
                        .as("昵称缺失 / 空白 / 被邀请人已注销时必须是 JSON null，不得填占位文本")
                        .isTrue();
            } else {
                assertThat(item.get("nickname").asText()).isEqualTo(expectedNickname);
            }

            // 其余三字段返回真实取值（需求 7.7）。
            assertThat(item.get("inviteId").asLong()).isEqualTo(relation.getInviteId());
            assertThat(MAPPER.treeToValue(item.get("registerTime"), LocalDateTime.class))
                    .isEqualTo(relation.getRegisterTime());
            assertThat(item.get("status").asText()).isEqualTo(relation.getStatus().name());
        }

        // ---- 邀请信息：三个字段，且只允许出现「自己的」邀请码 ----
        InviteInfoView infoView = fixture.service.getInviteInfo(fixture.inviterId);
        InviteInfoResponse infoResponse = InviteInfoResponse.from(infoView);
        JsonNode infoJson = assertFieldSet(infoResponse,
                Set.of("inviteCode", "inviteLink", "invitedCount"), FORBIDDEN_KEYS_EXCEPT_OWN_CODE);
        assertNoLeak(infoResponse, fixture.secretValuesExcept(scenario.inviter().inviteCode()));
        assertThat(infoJson.get("inviteCode").asText()).isEqualTo(scenario.inviter().inviteCode());

        // ---- 邀请人展示信息（公开端点）：只有昵称一个字段 ----
        String inviterNickname = fixture.service
                .findInviterNickname(scenario.inviter().inviteCode(), CLIENT_IP);
        InviterBriefResponse briefResponse = InviterBriefResponse.of(inviterNickname);
        JsonNode briefJson = assertFieldSet(briefResponse, Set.of("nickname"), FORBIDDEN_KEYS);
        assertNoLeak(briefResponse, fixture.allSecretValues());

        String expectedInviterNickname = blankToNull(scenario.inviter().nickname());
        if (expectedInviterNickname == null) {
            assertThat(briefJson.get("nickname").isNull()).isTrue();
        } else {
            assertThat(briefJson.get("nickname").asText()).isEqualTo(expectedInviterNickname);
        }

        // ---- 邀请二维码：只有图片一个字段 ----
        InviteQrCodeResponse qrResponse = InviteQrCodeResponse.of(QRCODE_BASE64);
        JsonNode qrJson = assertFieldSet(qrResponse, Set.of("imageBase64"), FORBIDDEN_KEYS);
        assertNoLeak(qrResponse, fixture.allSecretValues());
        assertThat(qrJson.get("imageBase64").asText()).isEqualTo(QRCODE_BASE64);
    }

    // ---------------- 断言工具 ----------------

    /** 顶层键集<strong>相等</strong>断言 + 全树键名不含任何被排除键；返回解析后的 JSON 便于继续断言。 */
    private static JsonNode assertFieldSet(Object dto, Set<String> expectedKeys,
            Set<String> forbiddenKeys) throws Exception {
        String json = MAPPER.writeValueAsString(dto);
        JsonNode node = MAPPER.readTree(json);

        assertThat(fieldNames(node))
                .as("%s 的顶层字段集必须恰为 %s（相等，不是包含）", dto.getClass().getSimpleName(), expectedKeys)
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(allFieldNames(node))
                .as("%s 序列化后不得出现任何被排除的键", dto.getClass().getSimpleName())
                .doesNotContainAnyElementsOf(forbiddenKeys);
        return node;
    }

    /** JSON 文本中不得出现被排除字段的<strong>取值</strong>（键名可以改，取值不会）。 */
    private static void assertNoLeak(Object dto, Set<String> forbiddenValues) throws Exception {
        String json = MAPPER.writeValueAsString(dto);
        for (String value : forbiddenValues) {
            assertThat(json)
                    .as("%s 序列化后不得出现被排除字段的取值：%s", dto.getClass().getSimpleName(), value)
                    .doesNotContain(value);
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 递归收集全树键名：嵌套对象（列表项）里的越权字段同样要挡住。 */
    private static Set<String> allFieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> sink) {
        if (node.isObject()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                sink.add(name);
                collectFieldNames(node.get(name), sink);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, sink));
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 项目的 Jackson 配置：走 Spring Boot 的 Jackson 自动配置，而不是裸 {@code new ObjectMapper()}。 */
    private static ObjectMapper projectObjectMapper() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.time-zone=Asia/Shanghai")
                .run(context -> ref.set(context.getBean(ObjectMapper.class)));
        return ref.get();
    }

    // ---------------- 生成数据模型 ----------------

    /** 一个用户身上「不该出现在邀请响应里」的取值，外加用于昵称语义断言的昵称。 */
    record Secrets(String email, String wxOpenid, String wxUnionid, String inviteCode,
                   String nickname) {
    }

    record InviteeSpec(Secrets secrets, boolean exists, InviteStatus status, int minuteOffset) {
    }

    record Scenario(Secrets inviter, List<InviteeSpec> invitees) {
    }

    // ---------------- 装置：真实 InviteService + 真实用户存储 ----------------

    /**
     * 每次迭代一套全新装置：{@link InMemoryUserRepository} 是真实存储实现（非 mock），
     * 因此昵称的空值语义、已注销被邀请人的「查不到」都由被测的映射逻辑真实产生。
     * 邀请关系仓库用测试替身：本属性不关心分页与排序（那是 Property 9），只关心映射出的字段。
     */
    private static final class Fixture {

        private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 3, 1, 10, 30, 0);

        private final InviteService service;
        private final long inviterId;
        private final List<InviteRelation> relations;
        private final Scenario scenario;
        /** 存活被邀请人的 id → 其昵称原文（可能为空白）。 */
        private final Map<Long, String> liveInviteeNicknames = new HashMap<>();

        private Fixture(Scenario scenario) {
            this.scenario = scenario;
            InMemoryUserRepository users = new InMemoryUserRepository();
            User inviter = users.save(newUser(scenario.inviter()));
            this.inviterId = inviter.getId();

            List<InviteRelation> built = new ArrayList<>();
            for (int i = 0; i < scenario.invitees().size(); i++) {
                InviteeSpec spec = scenario.invitees().get(i);
                long inviteeId;
                if (spec.exists()) {
                    User invitee = users.save(newUser(spec.secrets()));
                    inviteeId = invitee.getId();
                    liveInviteeNicknames.put(inviteeId, spec.secrets().nickname());
                } else {
                    // 已注销：users 行不存在，服务层只能拿到 null 昵称（需求 7.7、10.8）。
                    inviteeId = DELETED_INVITEE_ID_BASE + i;
                }
                InviteRelation relation = new InviteRelation();
                relation.setInviteId(INVITE_ID_BASE + i);
                relation.setInviterId(inviterId);
                relation.setInviteeId(inviteeId);
                relation.setRegisterTime(BASE_TIME.plusMinutes(spec.minuteOffset()));
                relation.setStatus(spec.status());
                relation.setCreatedAt(relation.getRegisterTime());
                relation.setUpdatedAt(relation.getRegisterTime());
                built.add(relation);
            }
            // 按 (register_time desc, invite_id desc) 预排，使替身返回的页与生产排序一致。
            built.sort(Comparator.comparing(InviteRelation::getRegisterTime)
                    .thenComparing(InviteRelation::getInviteId)
                    .reversed());
            this.relations = List.copyOf(built);

            InviteRelationRepository relationRepository = Mockito.mock(InviteRelationRepository.class);
            Page<InviteRelation> page = new PageImpl<>(relations,
                    PageRequest.of(0, 50), relations.size());
            Mockito.when(relationRepository.findByInviterId(Mockito.eq(inviterId), Mockito.any()))
                    .thenReturn(page);
            Mockito.when(relationRepository.countByInviterId(inviterId))
                    .thenReturn((long) relations.size());
            Mockito.when(relationRepository.countByInviterIdAndStatus(inviterId, InviteStatus.REGISTERED))
                    .thenReturn(registeredCount());

            InviteRateLimiter rateLimiter = Mockito.mock(InviteRateLimiter.class);
            Mockito.when(rateLimiter.tryAcquireInviterLookup(Mockito.anyString())).thenReturn(true);

            this.service = new InviteService(users, relationRepository, new InviteCodeGenerator(),
                    rateLimiter, Clock.fixed(Instant.parse("2025-06-01T04:00:00Z"),
                            ZoneId.of("Asia/Shanghai")));
        }

        private static User newUser(Secrets secrets) {
            User user = new User();
            user.setEmail(secrets.email());
            user.setNickname(secrets.nickname());
            user.setInviteCode(secrets.inviteCode());
            user.setWxOpenid(secrets.wxOpenid());
            user.setWxUnionid(secrets.wxUnionid());
            user.setCreatedAt(BASE_TIME);
            user.setUpdatedAt(BASE_TIME);
            user.setPlanStartedAt(BASE_TIME);
            user.setPlanExpiresAt(BASE_TIME.plusDays(365));
            return user;
        }

        private long registeredCount() {
            return relations.stream()
                    .filter(r -> r.getStatus() == InviteStatus.REGISTERED)
                    .count();
        }

        /** 期望昵称：被邀请人不存在、昵称为 NULL 或去空白为空一律 {@code null}。 */
        private String expectedNickname(Long inviteeId) {
            return blankToNull(liveInviteeNicknames.get(inviteeId));
        }

        /** 邀请人与全部被邀请人的邮箱 / openid / unionid / 邀请码：一个都不许出现在响应里。 */
        private Set<String> allSecretValues() {
            return secretValuesExcept(null);
        }

        private Set<String> secretValuesExcept(String allowedValue) {
            Set<String> values = new HashSet<>();
            addSecrets(values, scenario.inviter());
            scenario.invitees().forEach(spec -> addSecrets(values, spec.secrets()));
            values.remove(allowedValue);
            return values;
        }

        private static void addSecrets(Set<String> sink, Secrets secrets) {
            sink.add(secrets.email());
            sink.add(secrets.wxOpenid());
            sink.add(secrets.wxUnionid());
            sink.add(secrets.inviteCode());
        }
    }
}
