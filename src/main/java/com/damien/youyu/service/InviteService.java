package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.InviteRelation;
import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;

/**
 * 用户邀请系统的业务服务：个人邀请码的惰性补齐、邀请信息组装、被邀请人列表、邀请人展示信息查询。
 *
 * <p>本服务的数据范围<strong>硬性限定为令牌用户自身</strong>：所有方法只接受 {@code userId}
 * （由控制器从令牌解析），不接受任何"指定目标用户"的入参。</p>
 *
 * <p>注册路径上的邀请码写入不在本服务：那一步随 {@code users} 的 INSERT 一并完成
 * （见 {@code AuthService} 建号路径，需求 1.2、1.7）。本服务只负责存量用户
 * （迁移后 {@code invite_code} 为 NULL）的惰性补齐。</p>
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    /** 邀请链接的页面路径，与 {@code pages.json} 中的落地页注册路径一致（需求 2.1）。 */
    private static final String INVITE_LANDING_PATH = "/pages/invitelanding/invitelanding";

    /** 分页参数原文的合法形状：ASCII 十进制数字，可带一个正负号（需求 7.9）。 */
    private static final Pattern ASCII_DECIMAL = Pattern.compile("[+-]?[0-9]+");

    /** {@code page} 取值上限（需求 7.1、7.9）。 */
    static final int MAX_PAGE = 100000;
    /** {@code size} 取值上限（需求 7.1、7.9）。 */
    static final int MAX_SIZE = 50;
    /** {@code page} 缺省值。 */
    static final int DEFAULT_PAGE = 0;
    /** {@code size} 缺省值。 */
    static final int DEFAULT_SIZE = 20;

    /**
     * 邀请人展示信息查询三种失败情形共用的<b>唯一</b>提示文案（需求 8.9）。
     *
     * <p>格式非法、含非法字符、邀请码不存在必须返回逐字节相同的响应体，因此这条文案只能有一处定义，
     * 也不得把入参原文、长度、失败细分原因拼进文案——任何差异都会变成可用于枚举邀请码的信号。</p>
     */
    static final String INVITER_NOT_FOUND_MESSAGE = "邀请码不存在";

    private final UserRepository userRepository;
    private final InviteRelationRepository inviteRelationRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final InviteRateLimiter inviteRateLimiter;
    private final Clock clock;

    public InviteService(UserRepository userRepository,
                         InviteRelationRepository inviteRelationRepository,
                         InviteCodeGenerator inviteCodeGenerator,
                         InviteRateLimiter inviteRateLimiter,
                         Clock clock) {
        this.userRepository = userRepository;
        this.inviteRelationRepository = inviteRelationRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.inviteRateLimiter = inviteRateLimiter;
        this.clock = clock;
    }

    /**
     * 取当前用户的邀请码，为空则惰性补齐并持久化（需求 1.3、1.4、1.8、1.12、1.13）。
     *
     * <p>流程刻意固定为：{@code findForUpdateById} 取行级写锁 → 已非空直接返回 → 为空则按
     * 「最多 10 次抽取」策略生成并 {@code UPDATE}。</p>
     *
     * <p><b>为什么必须是 {@code findForUpdateById} 而不是 {@code findById}</b>（需求 1.12）：
     * 同一用户的两个请求并发触发补齐时，行级写锁把二者串行化——后到者进入临界区时已能读到非空取值，
     * 于是直接返回同一个码。终态恰好一个非空取值，两个响应中的邀请码相同。改用 {@code findById}
     * 会让两个请求各自生成一个码并先后写入，终态取值取决于提交顺序，且两个响应互相矛盾。</p>
     *
     * <p><b>幂等</b>（需求 1.4、1.13）：已非空时直接返回，不做任何写入——因此重复请求、改昵称、
     * 绑定/解绑邮箱或微信都不会改变该取值。邀请码没有修改与重置操作，仅在账号注销随 {@code users}
     * 行删除时释放。</p>
     *
     * <p><b>失败语义</b>（需求 1.8）：10 次候选码全被占用时由生成器抛
     * {@code INVITE_CODE_GEN_FAILED}，本方法不捕获——事务回滚使 {@code invite_code} 保持原值
     * （NULL），调用方的响应因而不含邀请码、邀请链接与已邀请人数中任何字段的值。</p>
     *
     * @param userId 令牌用户主键
     * @return 该用户的 8 位邀请码，非空
     * @throws ApiException {@code UNAUTHENTICATED}（令牌用户已不存在）/
     *                      {@code INVITE_CODE_GEN_FAILED}（连续 10 次候选码均被占用）
     */
    @Transactional
    public String requireInviteCode(Long userId) {
        // 行级写锁：同一用户的并发补齐在此串行化（需求 1.12）。
        User user = userRepository.findForUpdateById(userId)
                .orElseThrow(ApiException::unauthenticated);

        String existing = user.getInviteCode();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        // 抽取候选码；10 次全被占用则抛 INVITE_CODE_GEN_FAILED，事务回滚，invite_code 保持 NULL（需求 1.8）。
        String generated = inviteCodeGenerator.generateUnique(userRepository::existsByInviteCode);
        user.setInviteCode(generated);
        user.setUpdatedAt(LocalDateTime.now(clock));
        userRepository.save(user);
        log.info("邀请码惰性补齐完成：userId={}", userId);
        return generated;
    }

    /**
     * 邀请信息：惰性补齐邀请码 → 组装邀请链接 → 统计已邀请人数（需求 1.3、1.10、2.1、7.6）。
     *
     * <p>返回的字段<b>是且仅是</b>三个（需求 1.10）。数据范围硬性取 {@code userId}
     * （由控制器从令牌解析），本方法不接受任何用于指定目标用户的入参（需求 8.3）。</p>
     *
     * <p><b>为什么本方法也标 {@code @Transactional}</b>：内部对 {@link #requireInviteCode}
     * 的调用是<b>自调用</b>，不经过 Spring 代理，被调方法上的 {@code @Transactional} 因此不生效。
     * 惰性补齐依赖 {@code findForUpdateById} 的行级写锁，而悲观锁必须在事务内才有意义——所以事务
     * 边界必须由本方法自己声明。删掉这个注解会让并发补齐的串行化保证（需求 1.12）失效。</p>
     *
     * <p>失败语义（需求 1.8）：10 次候选码全被占用时 {@code INVITE_CODE_GEN_FAILED} 直接向外传播，
     * 调用方拿不到任何一个字段的值（不返回「部分填充」的视图）。</p>
     *
     * @param userId 令牌用户主键
     * @return 邀请码 + 邀请链接 + 已邀请人数
     * @throws ApiException {@code UNAUTHENTICATED} / {@code INVITE_CODE_GEN_FAILED}
     */
    @Transactional
    public InviteInfoView getInviteInfo(Long userId) {
        String inviteCode = requireInviteCode(userId);
        // 已邀请人数只数 REGISTERED：被邀请人注销后该行转为 INVALID，不再计入（需求 7.6、10.2）。
        long invitedCount = inviteRelationRepository
                .countByInviterIdAndStatus(userId, InviteStatus.REGISTERED);
        return new InviteInfoView(inviteCode, buildInviteLink(inviteCode), invitedCount);
    }

    /**
     * 被邀请人列表（原始字符串入参版）：先把 {@code page} / {@code size} 解析为整数，再委托给
     * {@link #listInvitees(Long, Integer, Integer)}。
     *
     * <p>供控制器直接传递查询参数原文使用：需求 7.9 把「无法解析为整数」与「越界」并列为同一个
     * {@code INVITE_PAGE_PARAM_INVALID}，因此解析失败必须落在同一条错误语义上，不能交给框架的
     * 类型转换异常（那会变成另一个错误码与另一套字段集）。缺失、{@code null} 或去空白为空一律
     * 按缺省值处理（{@code page}=0、{@code size}=20，需求 7.1）。</p>
     *
     * <p><b>为什么 page 必须先「解析 + 越界」两步都做完才碰 size</b>（需求 7.9）：既然两类失败
     * 共用同一个错误码，那么 page 的任何一类失败都必须压过 size 的任何一类失败，{@code field}
     * 才是确定的。写成
     * {@code listInvitees(userId, parsePageParam(rawPage, "page"), parsePageParam(rawSize, "size"))}
     * 会破坏这一点：Java 先把两个实参都求值，于是 {@code page} 越界（越界检查在被调方）而
     * {@code size} 不可解析（解析在实参求值处）时，先抛出的是 size 的解析异常，{@code field}
     * 变成 size。因此这里显式分两段：page 解析并越界校验完成后，才去解析 size。</p>
     *
     * <p>本方法同样标注事务：内部对整数版的调用是自调用、不经代理，事务边界只能在这里声明，
     * 否则三次查询（分页 + 两个计数）会各自开启事务，计数与列表可能取自不同的数据库快照。</p>
     *
     * @param userId  令牌用户主键
     * @param rawPage {@code page} 查询参数原文，可为 {@code null}
     * @param rawSize {@code size} 查询参数原文，可为 {@code null}
     * @throws ApiException {@code INVITE_PAGE_PARAM_INVALID}（{@code field} 为 page 或 size；
     *                      page 的解析失败与越界都优先于 size 的任何失败）
     */
    @Transactional(readOnly = true)
    public InviteeListView listInvitees(Long userId, String rawPage, String rawSize) {
        // page 先全部校验完（解析 + 越界），再动 size：保证 field 的优先级确定（需求 7.9）。
        int effectivePage = requireInRange(
                parsePageParam(rawPage, "page"), DEFAULT_PAGE, 0, MAX_PAGE, "page");
        Integer size = parsePageParam(rawSize, "size");
        return listInvitees(userId, effectivePage, size);
    }

    /**
     * 被邀请人列表：参数校验 → 固定排序 → 分页查询 → 批量补昵称（需求 7.1～7.10）。
     *
     * <p>排序固定为 {@code (register_time desc, invite_id desc)}（需求 7.2）：光按
     * {@code register_time} 排不够——同一时刻注册的两行在不同请求间的相对次序会不确定，翻页时同一条
     * 记录可能重复出现或被整页跳过。{@code invite_id} 作为第二排序键把次序钉成全序。</p>
     *
     * <p>返回的两个计数口径不同、都不受分页影响：{@code total} 含 {@code INVALID}（需求 7.5），
     * {@code invitedCount} 仅 {@code REGISTERED}（需求 7.6）。</p>
     *
     * <p>昵称由 {@code findAllById} 一次批量查出（单页至多 50 条，无 N+1）：映射中缺失（被邀请人
     * 已注销）或昵称去空白为空，一律填 {@code null}，<b>不用占位文本</b>，且不使本次请求失败
     * （需求 7.7、10.8）。列表项只有四个字段，不含被邀请人的 {@code email} / {@code wx_openid} /
     * {@code wx_unionid} / {@code invite_code}（需求 7.8）。</p>
     *
     * <p>全部查询硬性带 {@code inviter_id = userId}（需求 8.3）：仓库层没有「不带 inviterId」的
     * 列表或计数方法，越权读取他人数据无从表达。</p>
     *
     * @param userId 令牌用户主键
     * @param page   页码，{@code null} 取缺省 0；有效范围 0–100000
     * @param size   每页条数，{@code null} 取缺省 20；有效范围 1–50
     * @throws ApiException {@code INVITE_PAGE_PARAM_INVALID}（{@code field} 为 page 或 size）
     */
    @Transactional(readOnly = true)
    public InviteeListView listInvitees(Long userId, Integer page, Integer size) {
        // page 先于 size 校验：两者同时越界时 field 取 page，使错误响应确定（需求 7.9）。
        int effectivePage = requireInRange(page, DEFAULT_PAGE, 0, MAX_PAGE, "page");
        int effectiveSize = requireInRange(size, DEFAULT_SIZE, 1, MAX_SIZE, "size");

        Pageable pageable = PageRequest.of(effectivePage, effectiveSize,
                Sort.by(Sort.Order.desc("registerTime"), Sort.Order.desc("inviteId")));
        Page<InviteRelation> relations = inviteRelationRepository.findByInviterId(userId, pageable);

        Map<Long, String> nicknames = loadNicknames(relations.getContent());
        List<InviteeItemView> items = new ArrayList<>(relations.getNumberOfElements());
        for (InviteRelation relation : relations.getContent()) {
            items.add(new InviteeItemView(
                    relation.getInviteId(),
                    nicknames.get(relation.getInviteeId()),   // 缺失即 null（需求 7.7）
                    relation.getRegisterTime(),
                    relation.getStatus().name()));
        }

        long total = inviteRelationRepository.countByInviterId(userId);
        long invitedCount = inviteRelationRepository
                .countByInviterIdAndStatus(userId, InviteStatus.REGISTERED);
        return new InviteeListView(List.copyOf(items), total, invitedCount);
    }

    /**
     * 公开查询邀请人展示信息：<b>限流</b> → 规整 → 格式校验 → 查库（需求 4.2、4.4、8.5～8.10）。
     *
     * <p><b>四步顺序是需求的一部分，不可调换</b>：</p>
     * <ol>
     *   <li><b>限流判定必须最先</b>（需求 8.6）：本接口无需令牌，是全系统唯一可匿名探测「某邀请码是否
     *       存在」的入口。把限流放在格式校验或查库之后，攻击者就能用格式非法的码零成本地把请求打进来，
     *       更关键的是每次探测都会真的落一次 {@code users} 表查询——限流保护的正是数据库与枚举成本，
     *       而不只是响应内容。</li>
     *   <li>{@code normalize}：去首尾空白 + 转大写（需求 1.9，邀请码大小写不敏感）。</li>
     *   <li>{@code isWellFormed}：长度恰为 8 且字符全在字母表内。</li>
     *   <li>{@code findByInviteCode}：以规整后的取值精确匹配。</li>
     * </ol>
     *
     * <p><b>三种失败情形完全同构</b>（需求 8.9）：格式非法（长度不为 8）、含字母表以外的字符、
     * 规整后合法但库中查不到，三者一律抛同一个 {@code NOT_FOUND}、同一条
     * {@link #INVITER_NOT_FOUND_MESSAGE} 文案、同一个字段集 {@code {code, message, field}} 且
     * {@code field} 恒为 {@code null}（{@code ApiException.notFound} 不接受 {@code field} 参数，
     * 这条不变式因此在类型层面就成立）。不返回任何可区分「格式非法」与「不存在」的附加标识，
     * 否则响应差异本身就是一个可用于批量枚举邀请码的旁路信号。</p>
     *
     * <p><b>不存在时不追加等待、不重试</b>（需求 8.10）：耗时差异同样是可观测的旁路信号，而且刻意
     * 拉平耗时（sleep 补偿）只会把 500ms 的处理预算和限流额度一起浪费掉。这里的写法是「一次查询，
     * 两条路径都直接返回」——存在与不存在的代价天然接近。</p>
     *
     * <p><b>两种情形同等计入限流计数</b>（需求 8.10）：因为计数发生在第 1 步，此后走哪条分支都已
     * 记账完毕。反过来，<b>被限流拒绝的请求不消耗额度</b>（由 {@code InviteRateLimiter} 保证），
     * 且本方法在被拒时立即返回，不做任何查询，两张表数据不变（需求 8.7）。</p>
     *
     * <p><b>成功只返回昵称一个字段</b>（需求 4.2、8.5）：返回类型刻意是 {@code String} 而不是
     * {@code User} 或某个多字段视图——邀请人的 {@code id} / {@code email} / {@code wx_openid} /
     * {@code plan} / {@code role}、已邀请人数、注册时刻、账号状态一概不出这个方法。昵称为 NULL
     * 或去空白后为空时返回 {@code null}（以空值返回该字段，不用占位文本）。</p>
     *
     * @param rawCode  客户端传入的邀请码原文，可为 {@code null}
     * @param clientIp 限流键：{@code X-Forwarded-For} 末位或 TCP 远端地址（见 {@code InviteController}）
     * @return 邀请人昵称；昵称为 NULL 或空白时返回 {@code null}
     * @throws ApiException {@code INVITE_RATE_LIMITED}（429）/ {@code NOT_FOUND}（404，三种情形同构）
     */
    @Transactional(readOnly = true)
    public String findInviterNickname(String rawCode, String clientIp) {
        // 第 1 步：限流先于一切校验与查询（需求 8.6）。达上限时不消耗额度、不查库。
        if (!inviteRateLimiter.tryAcquireInviterLookup(clientIp)) {
            log.info("邀请人展示信息查询被限流：ip={}", clientIp);
            throw ApiException.inviteRateLimited();
        }

        // 第 2、3 步：规整后格式非法即按「查不到」处理，不抛别的异常、不返回服务端错误（需求 1.9）。
        String normalized = inviteCodeGenerator.normalize(rawCode);
        if (!inviteCodeGenerator.isWellFormed(normalized)) {
            throw inviterNotFound();
        }

        // 第 4 步：一次精确查询定胜负，查不到直接返回，不追加等待、不重试（需求 8.10）。
        User inviter = userRepository.findByInviteCode(normalized)
                .orElseThrow(InviteService::inviterNotFound);

        String nickname = inviter.getNickname();
        return (nickname == null || nickname.isBlank()) ? null : nickname;
    }

    /** 邀请人展示信息查询的唯一失败出口：三种情形共用，保证响应逐字段相同（需求 8.9）。 */
    private static ApiException inviterNotFound() {
        return ApiException.notFound(INVITER_NOT_FOUND_MESSAGE);
    }

    /**
     * 拼接邀请链接：{@code /pages/invitelanding/invitelanding?code={邀请码}}（需求 2.1）。
     *
     * <p>刻意<b>不做</b> URL 编码：邀请码的字母表是 {@code A-Z} 与 {@code 2-9} 的子集，全部是
     * URL 安全字符，转义只会把原文改坏（客户端拿 {@code code} 原文与邀请码比对时会不相等）。</p>
     */
    public static String buildInviteLink(String inviteCode) {
        return INVITE_LANDING_PATH + "?code=" + inviteCode;
    }

    /**
     * 取当前页被邀请人的昵称映射：{@code inviteeId → 昵称}。
     *
     * <p>只放<b>去空白后非空</b>的昵称：NULL、空串、全空白一律不入映射，于是调用方 {@code get}
     * 得到 {@code null}，与「已注销（{@code users} 行不存在）」是同一种表现（需求 7.7、10.8）。</p>
     */
    private Map<Long, String> loadNicknames(List<InviteRelation> relations) {
        if (relations.isEmpty()) {
            return Map.of();
        }
        List<Long> inviteeIds = relations.stream().map(InviteRelation::getInviteeId).toList();
        Map<Long, String> nicknames = new HashMap<>();
        for (User invitee : userRepository.findAllById(inviteeIds)) {
            String nickname = invitee.getNickname();
            if (nickname != null && !nickname.isBlank()) {
                nicknames.put(invitee.getId(), nickname);
            }
        }
        return nicknames;
    }

    /**
     * 解析分页参数原文：缺失 / 空白按缺省处理（返回 {@code null}），不可解析为整数即拒绝（需求 7.9）。
     *
     * <p><b>为什么先用 {@link #ASCII_DECIMAL} 卡形状，而不是只靠 {@code Integer.valueOf} 抛异常</b>：
     * {@code Integer.valueOf} 走 {@code Character.digit}，会接受任何 Unicode 十进制数字——全角
     * {@code "２０"}、阿拉伯-印度数字等都能被它解析成 20。那意味着两个字节序列不同的请求得到同一个
     * 生效分页，客户端无法从响应上区分自己传了什么；需求 7.9 把「非数字串」归入拒绝，这里因此只认
     * ASCII 十进制形状（可带一个正负号），其余一律拒绝。</p>
     */
    private static Integer parsePageParam(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (!ASCII_DECIMAL.matcher(trimmed).matches()) {
            throw ApiException.invitePageParamInvalid(field);
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            // 形状合法但超出 int 范围：与「非数字串」同一个错误码（需求 7.9）。
            throw ApiException.invitePageParamInvalid(field);
        }
    }

    /** {@code null} 取缺省值；越界抛 {@code INVITE_PAGE_PARAM_INVALID} 并把 {@code field} 置为参数名（需求 7.9）。 */
    private static int requireInRange(Integer value, int defaultValue, int min, int max, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw ApiException.invitePageParamInvalid(field);
        }
        return value;
    }
}
