package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.AchievementNoticeRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.CustomReminderRepository;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepaymentRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.ReminderSendLogRepository;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.TransactionTemplateRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 注销服务：注销的前置校验（协作牵连拦截）与级联硬删除。
 *
 * <p>任务(5.1) {@link #requireDeletable(Long)}：在真正删除前判断是否存在协作牵连，若存在则拒绝
 * 注销并抛出 {@code DELETE_BLOCKED_COLLAB}，提示用户先转交/删除相关账本或处理引用（需求 8.2）。
 * 任务(5.2) {@link #verifySecondFactor(Long, String, String)}：注销前的二次验证门禁（需求 8.1）。
 * 单事务级联硬删（任务 5.3）在后续任务补全。</p>
 *
 * <p>协作牵连的两个拦截条件（满足其一即拦截）：</p>
 * <ol>
 *   <li><b>协作账本仍有他人成员</b>：注销者拥有（{@code ledgers.user_id = userId}）的某账本，其成员中除本人外
 *       仍有其他成员（{@code ledger_members} 中存在 {@code user_id != userId} 的行）。直接删除会连带删除他人
 *       仍在协作的账本，故拦截。</li>
 *   <li><b>账户被他人流水引用</b>：注销者拥有（{@code accounts.user_id = userId}）的某账户，被「他人记账」
 *       （{@code transactions.created_by != userId}）的交易作为账户/源/目标引用。直接删除会孤立协作成员在
 *       共享账本里记的账，故拦截。</li>
 * </ol>
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** 成长数据删除耗时告警阈值（需求 12.9/12.10）：两步硬删合计超过 1000ms 记一条 WARN，但不中止注销事务。 */
    private static final long GROWTH_DELETE_SLOW_MS = 1000L;

    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final VerificationCodeService verificationCodeService;
    private final WeChatClient weChatClient;

    // 级联硬删（任务 5.3）所需的其余仓储：按 user_id / 拥有的账本·账户·交易 id 清理各表。
    private final AccountLedgerRepository accountLedgerRepository;
    private final TransactionTagRepository transactionTagRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryBudgetRepository categoryBudgetRepository;
    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final ProjectRepository projectRepository;
    private final MerchantRepository merchantRepository;
    private final TagRepository tagRepository;
    private final TransactionTemplateRepository templateRepository;
    private final LedgerInviteRepository inviteRepository;
    private final VerificationCodeRepository verificationCodeRepository;

    // 用户邀请关系（invite_relations）联动所需（任务 8.3，需求 10.2）：注销时把「该用户作为被邀请人」的
    // 那一行置 INVALID。时钟统一从容器注入（与其它 Service 一致），便于测试固定 updated_at。
    private final InviteRelationRepository inviteRelationRepository;
    private final Clock clock;

    // 成长数据（growth-level-system 任务 8.3，需求 12）：注销时在删 users 行之前硬删两表中该用户的行。
    // 两表均无指向 users(id) 的外键（需求 11.9），故由服务层在同一注销事务内显式删除。
    private final GrowthEventRepository growthEventRepository;
    private final UserGrowthRepository userGrowthRepository;

    // 成就播报游标（achievement-system 任务 7.2，需求 11）：注销时在删 users 行之前硬删该用户的游标行。
    // 该表同样没有指向 users(id) 的外键（与 user_growth 同一取舍），故由服务层在同一注销事务内显式删除。
    private final AchievementNoticeRepository achievementNoticeRepository;

    // 历史连续区间（streak-system 任务 6.2，需求 8）：注销时在删 users 行之前硬删该用户的段行。
    // 该表同样没有指向 users(id) 的外键（与 user_growth / achievement_notices 同一取舍），故由服务层在同一注销事务内显式删除。
    private final StreakSegmentRepository streakSegmentRepository;

    // 自定义提醒三表（custom-reminder 任务 9.1，需求 9.11/11.4）：注销时在删 users 行之前硬删该用户的
    // 提醒配置、订阅额度与发送记录。三表均无指向 users(id) 的外键（与 user_growth / achievement_notices /
    // streak_segments 同一取舍），故由服务层在同一注销事务内显式删除。
    private final ReminderSendLogRepository reminderSendLogRepository;
    private final CustomReminderRepository customReminderRepository;
    private final ReminderQuotaRepository reminderQuotaRepository;

    public AccountDeletionService(
            LedgerRepository ledgerRepository,
            LedgerMemberRepository memberRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            UserRepository userRepository,
            VerificationCodeService verificationCodeService,
            WeChatClient weChatClient,
            AccountLedgerRepository accountLedgerRepository,
            TransactionTagRepository transactionTagRepository,
            CategoryRepository categoryRepository,
            BudgetRepository budgetRepository,
            CategoryBudgetRepository categoryBudgetRepository,
            LoanRepository loanRepository,
            LoanRepaymentRepository loanRepaymentRepository,
            ProjectRepository projectRepository,
            MerchantRepository merchantRepository,
            TagRepository tagRepository,
            TransactionTemplateRepository templateRepository,
            LedgerInviteRepository inviteRepository,
            VerificationCodeRepository verificationCodeRepository,
            InviteRelationRepository inviteRelationRepository,
            GrowthEventRepository growthEventRepository,
            UserGrowthRepository userGrowthRepository,
            AchievementNoticeRepository achievementNoticeRepository,
            StreakSegmentRepository streakSegmentRepository,
            ReminderSendLogRepository reminderSendLogRepository,
            CustomReminderRepository customReminderRepository,
            ReminderQuotaRepository reminderQuotaRepository,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.verificationCodeService = verificationCodeService;
        this.weChatClient = weChatClient;
        this.accountLedgerRepository = accountLedgerRepository;
        this.transactionTagRepository = transactionTagRepository;
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
        this.categoryBudgetRepository = categoryBudgetRepository;
        this.loanRepository = loanRepository;
        this.loanRepaymentRepository = loanRepaymentRepository;
        this.projectRepository = projectRepository;
        this.merchantRepository = merchantRepository;
        this.tagRepository = tagRepository;
        this.templateRepository = templateRepository;
        this.inviteRepository = inviteRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.inviteRelationRepository = inviteRelationRepository;
        this.growthEventRepository = growthEventRepository;
        this.userGrowthRepository = userGrowthRepository;
        this.achievementNoticeRepository = achievementNoticeRepository;
        this.streakSegmentRepository = streakSegmentRepository;
        this.reminderSendLogRepository = reminderSendLogRepository;
        this.customReminderRepository = customReminderRepository;
        this.reminderQuotaRepository = reminderQuotaRepository;
        this.clock = clock;
    }

    /**
     * 注销前置校验：存在协作牵连则拒绝（需求 8.2）。无牵连则静默返回，可继续注销流程。
     *
     * @param userId 待注销用户 id
     * @throws ApiException {@code DELETE_BLOCKED_COLLAB}（409）当存在协作账本他人成员或账户被他人流水引用时
     */
    @Transactional(readOnly = true)
    public void requireDeletable(Long userId) {
        // 条件一：拥有的账本中，是否有仍存在「他人成员」的账本（协作账本邀请了其他成员）。
        List<Ledger> ownedLedgers = ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        for (Ledger ledger : ownedLedgers) {
            if (memberRepository.countByLedgerIdAndUserIdNot(ledger.getId(), userId) > 0) {
                throw ApiException.deleteBlockedCollab();
            }
        }

        // 条件二：拥有的账户中，是否有被「他人记账」的流水引用（协作成员用了本人共享账户记账）。
        List<Account> ownedAccounts = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        for (Account account : ownedAccounts) {
            if (transactionRepository.existsByAccountReferencedByOtherUser(account.getId(), userId)) {
                throw ApiException.deleteBlockedCollab();
            }
        }
    }

    /**
     * 注销二次验证门禁（需求 8.1）：在执行级联删除前，强制用户完成一次二次验证。
     *
     * <p>验证方式按账号身份分流（与需求 8.1 一致）：</p>
     * <ul>
     *   <li><b>邮箱身份用户</b>（{@code email} 非空白）：提交一枚 {@link EmailCodePurpose#DELETE} 用途的
     *       邮箱验证码。以 {@code verifyConsume(email, DELETE, code)} 单次消费校验，不通过 →
     *       {@code CODE_INVALID}。即便该用户同时绑定了微信，也以邮箱验证码为准（有可靠的邮件通道即优先）。</li>
     *   <li><b>纯微信用户</b>（无 email，仅有 {@code wx_openid}）：提交一枚新的微信一次性 {@code wxCode}
     *       做重新授权。{@code wxCode} 为空 → {@code WX_CODE_REQUIRED}；用 {@code jscode2session} 换取
     *       openid（换取失败沿用 {@code WX_LOGIN_FAILED}）；换回的 openid 必须与账号已存 {@code wx_openid}
     *       一致，否则说明并非本人授权 → {@code WX_LOGIN_FAILED("微信校验失败")}。</li>
     * </ul>
     *
     * <p>选择说明：openid 不匹配时采用 {@code WX_LOGIN_FAILED}（而非 {@code CODE_INVALID}），
     * 与微信登录/绑定域的失败语义保持一致（问题出在微信授权主体不符，而非验证码本身格式/时效）。
     * 校验成功静默返回（void），可继续注销流程；任一失败路径均抛出异常且不产生任何副作用
     * （本方法只读，不写库）。</p>
     *
     * @param userId 待注销用户 id
     * @param code   邮箱身份用户提交的 DELETE 用途验证码（纯微信用户忽略）
     * @param wxCode 纯微信用户提交的一次性微信授权码（邮箱身份用户忽略）
     * @throws ApiException UNAUTHENTICATED(会话用户不存在) / CODE_INVALID(邮箱验证码无效) /
     *                      WX_CODE_REQUIRED(微信授权码缺失) / WX_LOGIN_FAILED(换取失败或 openid 不匹配)
     */
    @Transactional(readOnly = true)
    public void verifySecondFactor(Long userId, String code, String wxCode) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);

        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        if (hasEmail) {
            // 邮箱身份用户：以 DELETE 用途单次消费校验验证码（需求 8.1）。
            if (!verificationCodeService.verifyConsume(user.getEmail(), EmailCodePurpose.DELETE, code)) {
                throw ApiException.codeInvalid();
            }
            return;
        }

        // 纯微信用户：重新授权换取 openid，且必须与账号已存 openid 一致（需求 8.1）。
        String normalizedWxCode = wxCode == null ? "" : wxCode.trim();
        if (normalizedWxCode.isEmpty()) {
            throw ApiException.wxCodeRequired();
        }
        WxSession session = weChatClient.jscode2session(normalizedWxCode);
        String openid = session.openid();
        if (openid == null || !openid.equals(user.getWxOpenid())) {
            throw ApiException.wxLoginFailed("微信校验失败");
        }
    }

    /**
     * 单事务级联硬删（任务 5.3，需求 8.3/8.4/8.5）：在<b>同一事务</b>内硬删注销者名下的全部数据与用户行本身。
     *
     * <p>本方法应在 {@link #requireDeletable(Long)}（协作牵连拦截，需求 8.2）与
     * {@link #verifySecondFactor(Long, String, String)}（二次验证，需求 8.1）通过之后调用。前者已保证：
     * 注销者拥有的账本不含他人成员、拥有的账户未被他人流水引用，因此级联仅清除注销者本人的数据足迹，
     * 不会孤立协作成员的数据。</p>
     *
     * <p><b>删除顺序遵循外键依赖（子/关联表先于父表，用户行最后）</b>，与生产 MySQL 的外键约束一致
     * （测试用 H2 由实体生成、无外键，顺序不影响结果，但仍保持一致）：</p>
     * <ol>
     *   <li>{@code transaction_tags}（按本人交易 id）——交易的子表；</li>
     *   <li>{@code transactions}（按 user_id，物理删除含回收站软删副本，需求 8.5 不留可恢复副本）；</li>
     *   <li>{@code category_budgets}、{@code budgets}、{@code loans}（按 user_id）；</li>
     *   <li>{@code categories}（按 user_id，位于交易/分类预算之后）；</li>
     *   <li>{@code account_ledger}（按本人拥有的账户 id 与账本 id，先于 accounts / ledgers）；</li>
     *   <li>{@code accounts}（按 user_id）；</li>
     *   <li>{@code transaction_templates}、{@code tags}、{@code projects}、{@code merchants}（按 user_id，先于 ledgers）；</li>
     *   <li>{@code ledger_invites}（本人创建的 + 本人拥有账本的，先于 ledgers）；</li>
     *   <li>{@code ledger_members}（本人的成员行，先于 ledgers / users）；</li>
     *   <li>{@code ledgers}（按 user_id，先于 users）；</li>
     *   <li>{@code verification_code}（按 email，干净释放邮箱身份）；</li>
     *   <li>{@code invite_relations}：把「该用户作为被邀请人」的那一行置 {@code INVALID}（不删行），
     *       必须早于 {@code users} 行的删除（邀请系统需求 10.2、10.3）；</li>
     *   <li>成长数据（第 12.5 步，成长体系需求 12）：先硬删 {@code growth_events} 中 {@code user_id} 等于
     *       该用户 id 的全部行、再硬删 {@code user_growth} 中该用户的行，置于 {@code invite_relations}
     *       置 {@code INVALID} 之后、删 {@code users} 行之前；两表均无外键、固定顺序只为可逐语句断言；</li>
     *   <li>成就播报游标（第 12.6 步，成就系统需求 11）：硬删 {@code achievement_notices} 中该用户的行，
     *       置于成长两表硬删之后、删 {@code users} 行之前；该表同样无外键、固定顺序只为可逐语句断言；</li>
     *   <li>{@code users}（用户行本身，最后）。删除用户行即释放其 {@code email} 与 {@code wx_openid}
     *       两个唯一键，供后续重新注册复用（需求 8.4），并随行释放 {@code invite_code}
     *       （邀请系统需求 10.4）。</li>
     * </ol>
     *
     * <p><b>用户邀请关系（{@code invite_relations}）只置状态、不删行</b>：以该用户 id 为
     * {@code inviter_id} 的行一行不动（含 {@code status}），保证「谁带来谁」的历史留痕与统计恒等式
     * （邀请系统需求 10.1）。详见下方对应步骤的注释。</p>
     *
     * <p>整个方法是单个 {@link Transactional} 单元：任一步失败则整体回滚，绝不产生部分删除
     * （需求 8.3）。全部为硬删除，不写任何软删/归档副本（需求 8.5）。</p>
     *
     * @param userId 待注销用户 id
     * @throws ApiException {@code UNAUTHENTICATED} 当用户不存在（会话用户已失效）
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        String email = user.getEmail();

        // 先收集「本人拥有的父级 id」，供关联表按父 id 批量清理（account_ledger / ledger_invites）。
        List<Long> ownedAccountIds = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)
                .stream().map(Account::getId).toList();
        List<Long> ownedLedgerIds = ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)
                .stream().map(Ledger::getId).toList();
        // 本人名下全部交易 id（含回收站软删记录，走原生 SQL 绕过 @SQLRestriction），供清理交易-标签关联。
        List<Long> transactionIds = transactionRepository.findAllIdsByUserId(userId);

        // 1) 交易-标签关联（交易子表）先删。
        if (!transactionIds.isEmpty()) {
            transactionTagRepository.deleteByTransactionIdIn(transactionIds);
        }
        // 2) 交易（物理删除，含回收站软删副本，不留可恢复副本，需求 8.5）。
        transactionRepository.hardDeleteByUserId(userId);
        // 3) 分类预算 / 月度预算 / 借贷。
        categoryBudgetRepository.deleteByUserId(userId);
        budgetRepository.deleteByUserId(userId);
        loanRepaymentRepository.deleteByUserId(userId);
        loanRepository.deleteByUserId(userId);
        // 4) 分类（在交易、分类预算之后）。
        categoryRepository.deleteByUserId(userId);
        // 5) 账户-账本可见性关联（先于 accounts / ledgers）。
        if (!ownedAccountIds.isEmpty()) {
            accountLedgerRepository.deleteByAccountIdIn(ownedAccountIds);
        }
        if (!ownedLedgerIds.isEmpty()) {
            accountLedgerRepository.deleteByLedgerIdIn(ownedLedgerIds);
        }
        // 6) 账户。
        accountRepository.deleteByUserId(userId);
        // 7) 账本下的目录型数据（模板/标签/项目/商家，先于 ledgers）。
        templateRepository.deleteByUserId(userId);
        tagRepository.deleteByUserId(userId);
        projectRepository.deleteByUserId(userId);
        merchantRepository.deleteByUserId(userId);
        // 8) 邀请码：本人创建的 + 本人拥有账本上的（先于 ledgers）。
        inviteRepository.deleteByCreatedBy(userId);
        if (!ownedLedgerIds.isEmpty()) {
            inviteRepository.deleteByLedgerIdIn(ownedLedgerIds);
        }
        // 9) 成员行：本人在各账本的成员记录（先于 ledgers / users）。
        memberRepository.deleteByUserId(userId);
        // 10) 账本（先于 users）。
        ledgerRepository.deleteByUserId(userId);
        // 11) 验证码：按邮箱清理，干净释放邮箱身份（需求 8.4）。
        if (email != null && !email.isBlank()) {
            verificationCodeRepository.deleteByEmail(email);
        }
        // 12) 用户邀请关系（invite_relations）联动，必须在删除 users 行之前（邀请系统需求 10.2、10.3）：
        //     把「该用户作为被邀请人」的那一行 status 置 INVALID，只改 status 与 updated_at，
        //     其余五列（invite_id / inviter_id / invitee_id / register_time / created_at）一律不动。
        //
        //     为什么是「先置 INVALID、再删 users 行」：invite_relations 的 invitee_id 刻意不建外键
        //     （需求 9），所以数据库层不约束顺序；但把更新排在删除之前，语义上与上面 1~11 步「由外向内、
        //     子表先于父表」的节奏一致，失败时的回滚范围也更直观。两步同处本方法的单个事务内，任一步失败
        //     整体回滚，users 与 invite_relations 全列还原（需求 10.5）。
        //     另外，本方法只在 requireDeletable（协作牵连拦截）与 verifySecondFactor（二次验证）都通过后
        //     才被调用，两者均为只读，因此前置校验失败时 invite_relations 零副作用（需求 10.6）。
        //
        //     为什么「该用户作为 inviter_id 的行」一行都不能碰（连 status 也不改，需求 10.1）：
        //     status 的语义被严格限定为「被邀请人的账号状态」。邀请人注销去改自己名下那些行的 status，
        //     会立刻破坏统计恒等式「总条数(total) − 已邀请人数(invitedCount) == INVALID 行数」。
        //     这些行注销后不再能被任何已认证接口读出（该账号已无法登录，且接口数据范围只认令牌用户 id），
        //     仅供后台统计（需求 10.10）。
        //     唯一索引 uk_invite_relations_invitee 保证本次更新影响行数 ≤ 1；返回 0 表示该用户不是任何人
        //     的被邀请人，属正常情况，无需处理。
        inviteRelationRepository.markInvalidByInviteeId(userId, LocalDateTime.now(clock));

        // 12.5) 成长数据硬删（growth-level-system 需求 12.1/12.2/12.5~12.11）：置于第 12 步（invite_relations
        //     置 INVALID）之后、第 13 步（删 users 行）之前，且不改变既有各步骤的相对顺序、过滤条件与影响行数
        //     （需求 12.8）。两表均无指向 users(id) 的外键（需求 11.9），删除顺序在数据库层没有约束；这里固定
        //     「先 growth_events、再 user_growth」只为使删除步骤可逐语句断言（需求 12.1）。
        //     两表无行时影响行数 0 即视为成功，删除前不做任何存在性预查询，也不写任何软删除或归档副本
        //     （需求 12.11、12.2）。整个 deleteAccount 是单个事务：这两步中任一步失败则整体回滚，users 与
        //     成长数据全列还原（需求 12.4）；且本方法只在 requireDeletable 与 verifySecondFactor（均只读）
        //     通过后才被调用，故前置校验失败时两表零副作用（需求 12.5）。成长数据无跨用户引用，删除不触及
        //     其它用户（需求 12.6），也不修改 invite_relations 任何行（需求 12.7，该表联动完全由第 12 步负责）。
        long growthDeleteStartedMs = clock.millis();
        growthEventRepository.deleteByUserId(userId);
        userGrowthRepository.deleteByUserId(userId);
        long growthDeleteCostMs = clock.millis() - growthDeleteStartedMs;
        if (growthDeleteCostMs > GROWTH_DELETE_SLOW_MS) {
            // 耗时超阈值只告警，不中止注销事务、不改变响应字段集与状态码（需求 12.10）。
            log.warn("[GROWTH_DELETE_SLOW] userId={} cost={}ms 超出 {}ms 预算",
                    userId, growthDeleteCostMs, GROWTH_DELETE_SLOW_MS);
        }

        // 12.6) 播报游标硬删（achievement-system 需求 11.1、11.2、11.4）：置于第 12.5 步（成长两表硬删）
        //     之后、第 13 步（删 users 行）之前，且不改变既有各步骤的相对顺序、过滤条件与影响行数（需求 11.2）。
        //     achievement_notices 无指向 users(id) 的外键（与 user_growth 同一取舍），删除顺序在数据库层
        //     没有约束；固定在这里只为使删除步骤可逐语句断言。以 user_id 等于该用户 id 为唯一过滤条件的
        //     1 条硬删除语句，影响行数 0 或 1：无行时影响行数 0 即视为成功，不返回错误标识、不中止注销事务
        //     （需求 11.3）。删除前不做任何存在性预查询，也不写该行的软删除标记、归档副本或更新语句
        //     （需求 11.4）。整个 deleteAccount 是单个事务：本步失败则整体回滚，users、成长两表与游标表
        //     全列还原（需求 11.5）；且本方法只在 requireDeletable 与 verifySecondFactor（均只读）通过后
        //     才被调用，故前置校验失败时游标表零副作用（需求 11.8）。游标行无跨用户引用，删除不触及其它
        //     用户（需求 11.7）。
        achievementNoticeRepository.deleteByUserId(userId);

        // 12.7) 历史连续区间硬删（streak-system 需求 8.8、8.9）：置于第 12.6 步（achievement_notices 硬删）
        //     之后、第 13 步（删 users 行）之前，且不改变既有各步骤的相对顺序、过滤条件与影响行数（需求 8.9）。
        //     streak_segments 无指向 users(id) 的外键（与 user_growth / achievement_notices 同一取舍），删除顺序
        //     在数据库层没有约束；固定在这里只为使删除步骤可逐语句断言。以 user_id 等于该用户 id 为唯一过滤条件的
        //     硬删除语句：无行时影响行数 0 即视为成功，不返回错误标识、不中止注销事务。删除前不做任何存在性预查询，
        //     也不写该行的软删除标记或归档副本。整个 deleteAccount 是单个事务：本步失败则整体回滚，users、成长两表、
        //     游标表与段表全列还原；且本方法只在 requireDeletable 与 verifySecondFactor（均只读）通过后才被调用，
        //     故前置校验失败时段表零副作用。段行无跨用户引用，删除不触及其它用户。
        streakSegmentRepository.deleteByUserId(userId);

        // 12.8) 自定义提醒三表硬删（custom-reminder 需求 9.11、11.4）：置于第 12.7 步（streak_segments 硬删）
        //     之后、第 13 步（删 users 行）之前，且不改变既有各步骤的相对顺序、过滤条件与影响行数。
        //     custom_reminders / reminder_quota / reminder_send_logs 三表均无指向 users(id) 的外键（与
        //     user_growth / achievement_notices / streak_segments 同一取舍），删除顺序在数据库层没有约束；
        //     这里固定「先 reminder_send_logs、再 custom_reminders、最后 reminder_quota」只为使删除步骤可逐语句
        //     断言。三表均以 user_id 等于该用户 id 为唯一过滤条件的硬删除语句：无行时影响行数 0 即视为成功，
        //     不返回错误标识、不中止注销事务；删除前不做任何存在性预查询，也不写软删除标记或归档副本。整个
        //     deleteAccount 是单个事务：这三步中任一步失败则整体回滚，users 与三表全列还原（需求 9.11、9.12），
        //     注销接口的响应字段集、HTTP 状态码与既有错误码不变（需求 11.4）。三表无跨用户引用，删除不触及
        //     其它用户，也只读/写这三张新表、不触及既有体系任何表。
        reminderSendLogRepository.deleteByUserId(userId);
        customReminderRepository.deleteByUserId(userId);
        reminderQuotaRepository.deleteByUserId(userId);

        // 13) 用户行本身：删除即释放 email 与 wx_openid 唯一键，供重新注册复用（需求 8.4、8.5）；
        //     同时随该行释放 users.invite_code，后续新用户可重新抽到同一个码——邀请关系的归属判定用的是
        //     inviter_id 而非邀请码取值，历史行不会串到新持有者名下（邀请系统需求 10.4、10.10）。
        userRepository.delete(user);
    }
}
