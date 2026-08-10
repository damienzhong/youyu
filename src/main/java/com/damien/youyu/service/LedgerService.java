package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerInvite;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepaymentRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.TransactionTemplateRepository;
import com.damien.youyu.service.aa.AaSettlementService;

/**
 * 账本服务：账本的列出、创建、重命名、删除，以及「默认账本」的惰性保障。
 *
 * <p>账本按 {@code userId} 归属用户。存量用户的默认账本由 Flyway 迁移(V8)创建；新注册用户在首个已认证
 * 业务请求解析当前账本时由 {@link #ensureDefaultLedger(Long)} 惰性创建（并预置默认分类），避免与鉴权耦合。</p>
 */
@Service
public class LedgerService {

    static final int NAME_MAX = 50;
    private static final String DEFAULT_NAME = "默认账本";

    private final LedgerRepository ledgerRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryBudgetRepository categoryBudgetRepository;
    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final LedgerMemberRepository memberRepository;
    private final LedgerInviteRepository inviteRepository;
    private final TransactionTemplateRepository templateRepository;
    private final ProjectRepository projectRepository;
    private final MerchantRepository merchantRepository;
    private final TagRepository tagRepository;
    private final TransactionTagRepository transactionTagRepository;
    private final AccountService accountService;
    private final InviteCodeGenerator inviteCodeGenerator;
    /**
     * AA 账本净额来源（需求 2.6：退出 / 移除前校验净额 = 0）。仅在 AA 账本的成员移除路径使用。
     *
     * <p><b>无循环依赖</b>：{@link AaSettlementService} 只依赖 Repository 与 {@link Clock}，不依赖
     * {@link LedgerService}，故此处构造器注入安全（详见 design.md「事务边界」与本任务说明）。</p>
     */
    private final AaSettlementService aaSettlementService;
    private final Clock clock;

    /** 邀请码有效期（天）。 */
    private static final int INVITE_TTL_DAYS = 7;

    public LedgerService(
            LedgerRepository ledgerRepository,
            CategoryRepository categoryRepository,
            AccountRepository accountRepository,
            AccountLedgerRepository accountLedgerRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            CategoryBudgetRepository categoryBudgetRepository,
            LoanRepository loanRepository,
            LoanRepaymentRepository loanRepaymentRepository,
            LedgerMemberRepository memberRepository,
            LedgerInviteRepository inviteRepository,
            TransactionTemplateRepository templateRepository,
            ProjectRepository projectRepository,
            MerchantRepository merchantRepository,
            TagRepository tagRepository,
            TransactionTagRepository transactionTagRepository,
            AccountService accountService,
            InviteCodeGenerator inviteCodeGenerator,
            AaSettlementService aaSettlementService,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.categoryBudgetRepository = categoryBudgetRepository;
        this.loanRepository = loanRepository;
        this.loanRepaymentRepository = loanRepaymentRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.templateRepository = templateRepository;
        this.projectRepository = projectRepository;
        this.merchantRepository = merchantRepository;
        this.tagRepository = tagRepository;
        this.transactionTagRepository = transactionTagRepository;
        this.accountService = accountService;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.aaSettlementService = aaSettlementService;
        this.clock = clock;
    }

    /** 列出某用户可访问的全部账本（自己拥有的 + 已加入的协作账本）；若一个都没有则先创建默认账本。 */
    @Transactional
    public List<Ledger> list(Long userId) {
        ensureDefaultLedger(userId);
        List<Long> ledgerIds = memberRepository.findByUserId(userId).stream()
                .map(LedgerMember::getLedgerId)
                .toList();
        List<Ledger> ledgers = ledgerRepository.findAllById(ledgerIds);
        // 排序：自己的默认账本置顶，其余按 sort_order、id 升序（加入的协作账本按其 owner 的排序值）。
        ledgers.sort(Comparator
                .comparing((Ledger l) -> !(l.getUserId().equals(userId) && l.isDefault()))
                .thenComparing(Ledger::getSortOrder)
                .thenComparing(Ledger::getId));
        return ledgers;
    }

    /**
     * 返回该用户的默认账本；不存在则创建（新用户首次访问的惰性初始化）。
     */
    @Transactional
    public Ledger ensureDefaultLedger(Long userId) {
        return ledgerRepository.findFirstByUserIdAndIsDefaultTrue(userId)
                .or(() -> ledgerRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(userId))
                .orElseGet(() -> createLedger(userId, DEFAULT_NAME, Ledger.TYPE_PERSONAL, 0, true));
    }

    /**
     * 校验当前用户可访问某账本（任一成员：OWNER/EDITOR）并返回；非成员抛 NOT_FOUND（不泄漏存在性）。
     * 读写业务数据（流水/分类/账户/预算）均以此为准。
     */
    @Transactional(readOnly = true)
    public Ledger requireAccessible(Long userId, Long ledgerId) {
        if (!memberRepository.existsByLedgerIdAndUserId(ledgerId, userId)) {
            throw ApiException.ledgerNotAccessible();
        }
        return ledgerRepository.findById(ledgerId)
                .orElseThrow(ApiException::ledgerNotAccessible);
    }

    /**
     * 校验当前用户为某账本 OWNER 并返回；非成员抛 NOT_FOUND，成员但非 OWNER 抛 FORBIDDEN。
     * 改名/删除/邀请/移除成员等管理操作以此为准。
     */
    @Transactional(readOnly = true)
    public Ledger requireOwner(Long userId, Long ledgerId) {
        LedgerMember member = memberRepository.findByLedgerIdAndUserId(ledgerId, userId)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        if (!member.isOwner()) {
            throw ApiException.ledgerForbidden();
        }
        return ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
    }

    /** 兼容旧调用：等价于 {@link #requireAccessible(Long, Long)}。 */
    @Transactional(readOnly = true)
    public Ledger requireOwned(Long userId, Long ledgerId) {
        return requireAccessible(userId, ledgerId);
    }

    /** 某用户是否为某账本成员（协作代记校验记账人归属用）。 */
    @Transactional(readOnly = true)
    public boolean isMember(Long ledgerId, Long userId) {
        return memberRepository.existsByLedgerIdAndUserId(ledgerId, userId);
    }

    /** 返回当前用户在某账本的角色（OWNER/EDITOR），非成员返回 null。 */
    @Transactional(readOnly = true)
    public String roleOf(Long userId, Long ledgerId) {
        return memberRepository.findByLedgerIdAndUserId(ledgerId, userId)
                .map(LedgerMember::getRole)
                .orElse(null);
    }

    /** 创建新账本（默认纳入该用户当前全部账户）。type：PERSONAL（默认）/ COLLABORATIVE。 */
    @Transactional
    public Ledger create(Long userId, String rawName, String rawType) {
        return create(userId, rawName, rawType, null);
    }

    /**
     * 创建新账本并选择纳入的账户（需求 3.2）。{@code accountIds} 为空表示默认全选当前用户的全部账户；
     * 传入的账户须归属该用户，为每个所选账户建立 {@code account_ledger} 关联行（默认对他人可见、显示余额）。
     */
    @Transactional
    public Ledger create(Long userId, String rawName, String rawType, java.util.List<Long> accountIds) {
        String name = validateName(rawName);
        String type = normalizeType(rawType);
        Ledger ledger = createLedger(userId, name, type, nextSortOrder(userId), false);
        // 用户主动创建的新账本预置一套默认收支分类，避免空账本、记第一笔前还得先建分类。
        seedDefaultCategories(userId, ledger.getId(), ledger.getCreatedAt());
        attachSelectedAccounts(userId, ledger.getId(), accountIds, ledger.getCreatedAt());
        return ledger;
    }

    /** 为新账本建立账户关联：accountIds 为空则全选用户账户；非本人账户忽略。 */
    private void attachSelectedAccounts(Long userId, Long ledgerId, java.util.List<Long> accountIds,
            LocalDateTime now) {
        java.util.List<Long> ids;
        if (accountIds == null) {
            ids = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                    .map(Account::getId)
                    .toList();
        } else {
            // 仅纳入归属该用户的账户（去重）。
            Set<Long> owned = new LinkedHashSet<>();
            for (Account a : accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
                owned.add(a.getId());
            }
            ids = accountIds.stream().filter(owned::contains).distinct().toList();
        }
        for (Long accId : ids) {
            com.damien.youyu.domain.AccountLedger al = new com.damien.youyu.domain.AccountLedger();
            al.setAccountId(accId);
            al.setLedgerId(ledgerId);
            al.setVisibleToOthers(true);
            // 隐私优先：共享账户默认可用于记账，但不向其他成员显示余额。
            al.setShowBalance(false);
            al.setCreatedAt(now);
            accountLedgerRepository.save(al);
        }
    }

    /**
     * 归一化账本类型：COLLABORATIVE（协作）/ AA（多人分摊）原样保留，其余（含旧别名 INDEPENDENT）
     * 一律回退 PERSONAL（个人）。AA 账本创建后由 {@link #createLedger} 登记创建者为 OWNER 成员
     * （需求 1.1），并同样预置默认分类（记账需要），归档字段默认为空（未归档）。
     */
    private String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return Ledger.TYPE_PERSONAL;
        }
        String t = rawType.trim().toUpperCase();
        if (Ledger.TYPE_COLLABORATIVE.equals(t)) {
            return Ledger.TYPE_COLLABORATIVE;
        }
        if (Ledger.TYPE_AA.equals(t)) {
            return Ledger.TYPE_AA;
        }
        return Ledger.TYPE_PERSONAL;
    }

    /**
     * 归档 AA 账本（置 {@code archived_at}，OWNER-only，需求 8.3、8.4）。归档后账本<b>只读</b>——AA 写操作
     * （记账 / 编辑 / 删除 / 结清 / 撤销）由各 AA 服务的 {@code ledger.isArchived()} 判定拒绝
     * （{@code AA_LEDGER_ARCHIVED}）；概览 / 结算等只读视图仍可访问、导出保留（需求 8.3）。
     *
     * <p><b>范围：</b>仅 AA 账本支持归档（非 AA 抛 {@code AA_ARCHIVE_NOT_SUPPORTED}）。只读判定只在 AA 写
     * 路径生效，对个人 / 家庭账本置归档态不会真正只读，故直接拒绝、避免半生效归档态。</p>
     *
     * <p><b>未结清二次确认（需求 8.4）：</b>归档时仍有成员净额非 0（应收 / 应付未结清）且 {@code force=false}
     * 时抛 {@code AA_LEDGER_UNSETTLED}，前端据此弹确认框；用户确认后带 {@code force=true} 重试即可归档。
     * 已全部结清时无需 {@code force}。净额口径复用 {@link AaSettlementService#netCentsByUser}。</p>
     *
     * <p>幂等：已归档账本再次归档原样返回（不刷新时间、不再校验），避免重复操作报错。</p>
     *
     * @param userId 当前用户（须为 OWNER）
     * @param id     账本 id
     * @param force  未结清时是否强制归档（二次确认）
     * @return 归档后的账本
     * @throws ApiException NOT_FOUND（非成员）、LEDGER_FORBIDDEN（非 OWNER）、
     *                      AA_ARCHIVE_NOT_SUPPORTED（非 AA 账本）、AA_LEDGER_UNSETTLED（未结清且未 force）
     */
    @Transactional
    public Ledger archive(Long userId, Long id, boolean force) {
        Ledger ledger = requireOwner(userId, id);
        if (!ledger.isAa()) {
            throw ApiException.aaArchiveNotSupported();
        }
        if (ledger.isArchived()) {
            return ledger; // 幂等：已归档原样返回。
        }
        if (!force && hasUnsettledNet(id)) {
            throw ApiException.aaLedgerUnsettled();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ledger.setArchivedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger);
    }

    /**
     * 解档 AA 账本（清空 {@code archived_at}，OWNER-only，需求 8.5），恢复其可编辑状态（AA 写操作重新放行）。
     * 仅 AA 账本支持（非 AA 抛 {@code AA_ARCHIVE_NOT_SUPPORTED}）；未归档账本再次解档幂等返回。
     *
     * @throws ApiException NOT_FOUND（非成员）、LEDGER_FORBIDDEN（非 OWNER）、
     *                      AA_ARCHIVE_NOT_SUPPORTED（非 AA 账本）
     */
    @Transactional
    public Ledger unarchive(Long userId, Long id) {
        Ledger ledger = requireOwner(userId, id);
        if (!ledger.isAa()) {
            throw ApiException.aaArchiveNotSupported();
        }
        if (!ledger.isArchived()) {
            return ledger; // 幂等：未归档原样返回。
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ledger.setArchivedAt(null);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger);
    }

    /** 账本是否仍有未结清净额（任一成员净额非 0）。净额口径复用 {@link AaSettlementService#netCentsByUser}。 */
    private boolean hasUnsettledNet(Long ledgerId) {
        return aaSettlementService.netCentsByUser(ledgerId).values().stream()
                .anyMatch(cents -> cents != 0L);
    }

    /** 重命名账本。 */
    @Transactional
    public Ledger rename(Long userId, Long id, String rawName) {
        String name = validateName(rawName);
        Ledger ledger = requireOwner(userId, id);
        ledger.setName(name);
        ledger.setUpdatedAt(LocalDateTime.now(clock));
        return ledgerRepository.save(ledger);
    }

    /**
     * 删除账本并级联清除其全部业务数据。至少保留一个账本；删除默认账本时把默认标记转移到剩余账本之一。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Ledger ledger = requireOwner(userId, id);
        if (ledgerRepository.countByUserId(userId) <= 1) {
            throw ApiException.ledgerLastOne();
        }
        // 账户是独立实体、跨账本共享，删除账本不删账户；先记录受影响账户，删除该账本流水后重算其余额。
        Set<Long> affectedAccountIds = new LinkedHashSet<>();
        for (Transaction t : transactionRepository.findByLedgerId(id)) {
            if (t.getAccountId() != null) {
                affectedAccountIds.add(t.getAccountId());
            }
            if (t.getSourceAccountId() != null) {
                affectedAccountIds.add(t.getSourceAccountId());
            }
            if (t.getDestinationAccountId() != null) {
                affectedAccountIds.add(t.getDestinationAccountId());
            }
        }

        // 级联清除该账本的业务数据（含回收站中的软删除记录，故用物理删除）。
        transactionRepository.hardDeleteByLedgerId(id);
        categoryBudgetRepository.deleteByLedgerId(id);
        budgetRepository.deleteByLedgerId(id);
        // 借贷已回归用户级，不随账本删除（其账户余额影响独立于账本）。
        templateRepository.deleteByLedgerId(id);
        projectRepository.deleteByLedgerId(id);
        merchantRepository.deleteByLedgerId(id);
        transactionTagRepository.deleteByLedgerId(id);
        tagRepository.deleteByLedgerId(id);
        categoryRepository.deleteByLedgerId(id);
        inviteRepository.deleteByLedgerId(id);
        memberRepository.deleteByLedgerId(id);
        // 账户与账本的可见性关联随账本删除（账户本身保留）。
        accountLedgerRepository.deleteByLedgerId(id);
        ledgerRepository.delete(ledger);

        // 账户跨账本共享：不删账户，重算受影响账户余额（初始余额 + 其余剩余流水/转账）。
        for (Long accountId : affectedAccountIds) {
            accountService.recomputeAndSave(accountId);
        }

        // 若删的是默认账本，把默认标记转移到剩余排序第一的账本。
        if (ledger.isDefault()) {
            ledgerRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(userId).ifPresent(next -> {
                next.setDefault(true);
                next.setUpdatedAt(LocalDateTime.now(clock));
                ledgerRepository.save(next);
            });
        }
    }

    private Ledger createLedger(Long userId, String name, String type, int sortOrder, boolean isDefault) {
        LocalDateTime now = LocalDateTime.now(clock);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName(name);
        ledger.setType(type);
        ledger.setSortOrder(sortOrder);
        ledger.setDefault(isDefault);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(ledger);
        // 创建者即 OWNER 成员（访问控制真源）。
        LedgerMember owner = new LedgerMember();
        owner.setLedgerId(saved.getId());
        owner.setUserId(userId);
        owner.setRole(LedgerMember.ROLE_OWNER);
        owner.setCreatedAt(now);
        memberRepository.save(owner);
        return saved;
    }

    /** 新账本默认分类树（两级：父 + 子）——见 {@link DefaultCategories}，与 onboarding 补齐保持一致。 */
    private void seedDefaultCategories(Long userId, Long ledgerId, LocalDateTime now) {
        seedTree(userId, ledgerId, CategoryKind.EXPENSE, DefaultCategories.EXPENSE, now);
        seedTree(userId, ledgerId, CategoryKind.INCOME, DefaultCategories.INCOME, now);
    }

    /** 落库一组父分类及其子分类：先存父拿到 id，再挂子分类。 */
    private void seedTree(Long userId, Long ledgerId, CategoryKind kind,
            DefaultCategories.Group[] groups, LocalDateTime now) {
        for (DefaultCategories.Group g : groups) {
            Category parent = categoryRepository.save(newCategory(userId, ledgerId, kind, g.name(), null, now));
            for (String child : g.children()) {
                categoryRepository.save(newCategory(userId, ledgerId, kind, child, parent.getId(), now));
            }
        }
    }

    private Category newCategory(Long userId, Long ledgerId, CategoryKind kind, String name,
            Long parentId, LocalDateTime now) {
        Category c = new Category();
        c.setUserId(userId);
        c.setLedgerId(ledgerId);
        c.setParentId(parentId);
        c.setKind(kind);
        c.setName(name);
        c.setIcon(CategoryIcons.guess(name, kind));
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    // ---------------- 协作：邀请 / 加入 / 成员管理 ----------------

    /**
     * OWNER 为协作账本或 AA 账本生成一个带有效期的邀请码（需求 2.1）。个人账本（PERSONAL）无成员语义，
     * 拒绝邀请。AA 账本复用同一套邀请码机制，受邀人加入后成为可参与分摊的 EDITOR 成员。
     */
    @Transactional
    public LedgerInvite createInvite(Long userId, Long ledgerId) {
        Ledger ledger = requireOwner(userId, ledgerId);
        if (!ledger.isCollaborative() && !ledger.isAa()) {
            throw ApiException.ledgerNotCollaborative();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LedgerInvite invite = new LedgerInvite();
        invite.setCode(generateUniqueCode());
        invite.setLedgerId(ledgerId);
        invite.setCreatedBy(userId);
        invite.setExpiresAt(now.plusDays(INVITE_TTL_DAYS));
        invite.setCreatedAt(now);
        return inviteRepository.save(invite);
    }

    /**
     * 凭邀请码加入协作账本或 AA 账本为 EDITOR 成员（已是成员则幂等返回）。返回目标账本。
     *
     * <p>加入要求 {@code userId} 为已登录的注册用户——本方法由已鉴权的接口调用，{@code userId} 取自
     * 当前登录主体（{@link com.damien.youyu.security.CurrentUser}），未登录请求在鉴权层即被拒（401），
     * 因此不存在「虚拟 / 未注册参与人」（需求 2.2、2.3、2.4）。AA 账本加入者与协作账本一致取
     * {@link LedgerMember#ROLE_EDITOR}，创建者仍为 OWNER。</p>
     */
    @Transactional
    public Ledger join(Long userId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (code.isEmpty()) {
            throw ApiException.inviteInvalid();
        }
        LedgerInvite invite = inviteRepository.findByCode(code)
                .orElseThrow(ApiException::inviteInvalid);
        LocalDateTime now = LocalDateTime.now(clock);
        if (invite.getExpiresAt().isBefore(now)) {
            throw ApiException.inviteInvalid();
        }
        Ledger ledger = ledgerRepository.findById(invite.getLedgerId())
                .orElseThrow(ApiException::inviteInvalid);
        if (!ledger.isCollaborative() && !ledger.isAa()) {
            throw ApiException.ledgerNotCollaborative();
        }
        if (!memberRepository.existsByLedgerIdAndUserId(ledger.getId(), userId)) {
            LedgerMember member = new LedgerMember();
            member.setLedgerId(ledger.getId());
            member.setUserId(userId);
            member.setRole(LedgerMember.ROLE_EDITOR);
            member.setCreatedAt(now);
            memberRepository.save(member);
        }
        return ledger;
    }

    /** 列出某账本全部成员（需为成员）。 */
    @Transactional(readOnly = true)
    public List<LedgerMember> members(Long userId, Long ledgerId) {
        requireAccessible(userId, ledgerId);
        return memberRepository.findByLedgerId(ledgerId);
    }

    /**
     * 移除成员：OWNER 可移除任一 EDITOR；成员可移除自己（退出）。不可移除 OWNER（需求 2.8）。
     *
     * <p><b>AA 账本额外约束（需求 2.6、2.7）：</b> 目标成员仍有未结清净额（应收或应付非 0）时，
     * 阻止退出 / 移除并抛 {@code AA_MEMBER_UNSETTLED}，须先结清（净额 = 0）后再操作。净额口径复用
     * {@link AaSettlementService#netCentsByUser}（以「分」为单位，未撤销支出与结算派生）。成功移除
     * <b>仅</b>将其移出成员列表，其全部历史流水与分摊记录保留（不删除），移出后不再是成员即无法参与
     * 新笔分摊 / 不计入新记账默认参与人（{@code AaExpenseService} 的成员校验会拒绝非成员作为付款人 /
     * 参与人，见 {@code AaExpenseService.distinctParticipants}）。协作 / 个人账本无此净额约束，行为不变。</p>
     */
    @Transactional
    public void removeMember(Long userId, Long ledgerId, Long targetUserId) {
        LedgerMember target = memberRepository.findByLedgerIdAndUserId(ledgerId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("成员不存在"));
        if (target.isOwner()) {
            throw ApiException.memberOwnerImmutable();
        }
        boolean isSelfLeave = targetUserId.equals(userId);
        Ledger ledger;
        if (!isSelfLeave) {
            ledger = requireOwner(userId, ledgerId); // 移除他人须为 OWNER
        } else {
            ledger = requireAccessible(userId, ledgerId); // 退出须为成员
        }
        // AA 账本：仅净额为 0 才可退出 / 移除（需求 2.6）；否则阻止并提示先结清。
        if (ledger.isAa()) {
            long netCents = aaSettlementService.netCentsByUser(ledgerId).getOrDefault(targetUserId, 0L);
            if (netCents != 0L) {
                throw ApiException.aaMemberUnsettled();
            }
        }
        // 取消该成员账户在此账本的暴露（未来不可选）；历史流水与余额影响保留（需求 2.7、8.3）。
        java.util.List<Long> memberAccountIds =
                accountRepository.findByUserIdOrderBySortOrderAscIdAsc(targetUserId).stream()
                        .map(Account::getId)
                        .toList();
        if (!memberAccountIds.isEmpty()) {
            accountLedgerRepository.deleteByAccountIdInAndLedgerId(memberAccountIds, ledgerId);
        }
        memberRepository.deleteByLedgerIdAndUserId(ledgerId, targetUserId);
    }

    /**
     * 生成一个未被占用的账本邀请码，委托给 {@link InviteCodeGenerator}（需求 1.6）。
     *
     * <p>字母表（32 字符，剔除 {@code I}/{@code O}/{@code 0}/{@code 1}）、长度 8 与「最多 10 次重试」
     * 策略与用户邀请码完全一致，此前是本类里的一份独立副本，现收敛到唯一定义处。</p>
     *
     * <p><b>两套邀请机制仍彼此独立</b>：这里的占用判定查的是 {@code ledger_invites.code}，
     * 与用户邀请码查的 {@code users.invite_code} 各自成一套码空间，允许取值重合。只共用
     * 「怎么抽码」，不共用「码归谁」。</p>
     */
    private String generateUniqueCode() {
        return inviteCodeGenerator.generateUnique(code -> inviteRepository.findByCode(code).isPresent());
    }

    private int nextSortOrder(Long userId) {
        return ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .mapToInt(Ledger::getSortOrder)
                .max()
                .orElse(-1) + 1;
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw ApiException.ledgerNameInvalid();
        }
        return name;
    }
}
