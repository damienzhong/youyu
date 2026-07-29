package com.damien.youyu.service;

import java.security.SecureRandom;
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
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.MerchantRepository;
import com.damien.youyu.repository.ProjectRepository;
import com.damien.youyu.repository.TagRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionTagRepository;
import com.damien.youyu.repository.TransactionTemplateRepository;

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
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryBudgetRepository categoryBudgetRepository;
    private final LoanRepository loanRepository;
    private final LedgerMemberRepository memberRepository;
    private final LedgerInviteRepository inviteRepository;
    private final TransactionTemplateRepository templateRepository;
    private final ProjectRepository projectRepository;
    private final MerchantRepository merchantRepository;
    private final TagRepository tagRepository;
    private final TransactionTagRepository transactionTagRepository;
    private final AccountService accountService;
    private final Clock clock;

    /** 邀请码有效期（天）。 */
    private static final int INVITE_TTL_DAYS = 7;
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    public LedgerService(
            LedgerRepository ledgerRepository,
            CategoryRepository categoryRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            CategoryBudgetRepository categoryBudgetRepository,
            LoanRepository loanRepository,
            LedgerMemberRepository memberRepository,
            LedgerInviteRepository inviteRepository,
            TransactionTemplateRepository templateRepository,
            ProjectRepository projectRepository,
            MerchantRepository merchantRepository,
            TagRepository tagRepository,
            TransactionTagRepository transactionTagRepository,
            AccountService accountService,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.categoryBudgetRepository = categoryBudgetRepository;
        this.loanRepository = loanRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.templateRepository = templateRepository;
        this.projectRepository = projectRepository;
        this.merchantRepository = merchantRepository;
        this.tagRepository = tagRepository;
        this.transactionTagRepository = transactionTagRepository;
        this.accountService = accountService;
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
                .orElseGet(() -> createLedger(userId, DEFAULT_NAME, "INDEPENDENT", 0, true));
    }

    /**
     * 校验当前用户可访问某账本（任一成员：OWNER/EDITOR）并返回；非成员抛 NOT_FOUND（不泄漏存在性）。
     * 读写业务数据（流水/分类/账户/预算）均以此为准。
     */
    @Transactional(readOnly = true)
    public Ledger requireAccessible(Long userId, Long ledgerId) {
        if (!memberRepository.existsByLedgerIdAndUserId(ledgerId, userId)) {
            throw ApiException.notFound("账本不存在");
        }
        return ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
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

    /** 创建新账本。type：INDEPENDENT（默认）/ COLLABORATIVE。 */
    @Transactional
    public Ledger create(Long userId, String rawName, String rawType) {
        String name = validateName(rawName);
        String type = normalizeType(rawType);
        Ledger ledger = createLedger(userId, name, type, nextSortOrder(userId), false);
        // 用户主动创建的新账本预置一套默认收支分类，避免空账本、记第一笔前还得先建分类。
        // （自动创建的默认账本不种子，保持与既有行为一致。）
        seedDefaultCategories(userId, ledger.getId(), ledger.getCreatedAt());
        return ledger;
    }

    private String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "INDEPENDENT";
        }
        String t = rawType.trim().toUpperCase();
        return "COLLABORATIVE".equals(t) ? "COLLABORATIVE" : "INDEPENDENT";
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
        // 账户为用户级、跨账本共享，删除账本不删账户；先记录受影响账户，删除该账本流水后重算其余额。
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

        boolean collaborative = "COLLABORATIVE".equals(ledger.getType());

        // 级联清除该账本的业务数据。
        transactionRepository.deleteByLedgerId(id);
        categoryBudgetRepository.deleteByLedgerId(id);
        budgetRepository.deleteByLedgerId(id);
        loanRepository.deleteByLedgerId(id);
        templateRepository.deleteByLedgerId(id);
        projectRepository.deleteByLedgerId(id);
        merchantRepository.deleteByLedgerId(id);
        transactionTagRepository.deleteByLedgerId(id);
        tagRepository.deleteByLedgerId(id);
        categoryRepository.deleteByLedgerId(id);
        inviteRepository.deleteByLedgerId(id);
        memberRepository.deleteByLedgerId(id);
        if (collaborative) {
            // 协作账本的账户为账本级，随账本删除。
            accountRepository.deleteByLedgerId(id);
        }
        ledgerRepository.delete(ledger);

        if (!collaborative) {
            // 独立账本的账户为用户级、跨账本共享：不删账户，重算受影响账户余额（初始余额 + 其余账本剩余流水）。
            LocalDateTime now = LocalDateTime.now(clock);
            for (Long accountId : affectedAccountIds) {
                accountRepository.findByIdAndUserIdAndLedgerIdIsNull(accountId, userId).ifPresent(account -> {
                    account.setCurrentBalance(accountService.recomputeBalance(userId, accountId));
                    account.setUpdatedAt(now);
                    accountRepository.save(account);
                });
            }
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

    /** 新账本默认分类（扁平，收支各一组常用项）。 */
    private static final String[] DEFAULT_EXPENSE_CATEGORIES = {
            "餐饮", "交通", "购物", "居住", "娱乐", "医疗", "通讯", "人情"
    };
    private static final String[] DEFAULT_INCOME_CATEGORIES = {
            "工资", "兼职", "理财", "红包"
    };

    private void seedDefaultCategories(Long userId, Long ledgerId, LocalDateTime now) {
        for (String name : DEFAULT_EXPENSE_CATEGORIES) {
            categoryRepository.save(newCategory(userId, ledgerId, CategoryKind.EXPENSE, name, now));
        }
        for (String name : DEFAULT_INCOME_CATEGORIES) {
            categoryRepository.save(newCategory(userId, ledgerId, CategoryKind.INCOME, name, now));
        }
    }

    private Category newCategory(Long userId, Long ledgerId, CategoryKind kind, String name,
            LocalDateTime now) {
        Category c = new Category();
        c.setUserId(userId);
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(name);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    // ---------------- 协作：邀请 / 加入 / 成员管理 ----------------

    /** OWNER 为协作账本生成一个带有效期的邀请码。 */
    @Transactional
    public LedgerInvite createInvite(Long userId, Long ledgerId) {
        Ledger ledger = requireOwner(userId, ledgerId);
        if (!"COLLABORATIVE".equals(ledger.getType())) {
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

    /** 凭邀请码加入协作账本为 EDITOR 成员（已是成员则幂等返回）。返回目标账本。 */
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
        if (!"COLLABORATIVE".equals(ledger.getType())) {
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
     * 移除成员：OWNER 可移除任一 EDITOR；成员可移除自己（退出）。不可移除 OWNER。
     */
    @Transactional
    public void removeMember(Long userId, Long ledgerId, Long targetUserId) {
        LedgerMember target = memberRepository.findByLedgerIdAndUserId(ledgerId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("成员不存在"));
        if (target.isOwner()) {
            throw ApiException.memberOwnerImmutable();
        }
        boolean isSelfLeave = targetUserId.equals(userId);
        if (!isSelfLeave) {
            requireOwner(userId, ledgerId); // 移除他人须为 OWNER
        } else {
            requireAccessible(userId, ledgerId); // 退出须为成员
        }
        memberRepository.deleteByLedgerIdAndUserId(ledgerId, targetUserId);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LEN);
            for (int i = 0; i < CODE_LEN; i++) {
                sb.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();
            if (inviteRepository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new ApiException("INVITE_CODE_GEN_FAILED",
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "邀请码生成失败，请重试", null);
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
