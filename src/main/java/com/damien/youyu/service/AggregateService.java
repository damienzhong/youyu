package com.damien.youyu.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 「全部账本」聚合只读服务：跨当前用户的所有账本汇总账户与交易，供首页「全部」视图使用。
 *
 * <p>独立于按账本隔离的业务服务，仅提供只读聚合，不参与写入（写入必须落到具体账本）。
 * 通过 {@link LedgerRepository} 取当前用户的账本 id 集合，再以 {@code ledgerId IN (...)} 聚合查询。</p>
 */
@Service
public class AggregateService {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final com.damien.youyu.repository.LedgerMemberRepository memberRepository;

    public AggregateService(
            LedgerRepository ledgerRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            com.damien.youyu.repository.LedgerMemberRepository memberRepository) {
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.memberRepository = memberRepository;
    }

    /** 当前用户全部账本的分类（跨账本聚合，供「全部」视图解析分类名）。 */
    @Transactional(readOnly = true)
    public List<Category> allCategories(Long userId) {
        List<Long> ledgerIds = ledgerIds(userId);
        return ledgerIds.isEmpty() ? List.of() : categoryRepository.findByLedgerIdIn(ledgerIds);
    }

    /**
     * 「全部」视图账户：账户是独立于账本的一等实体，均归属用户，故直接返回该用户拥有的全部账户
     * （净资产按账户口径，跨账本共享的账户不重复计入）。
     */
    @Transactional(readOnly = true)
    public List<Account> allAccounts(Long userId) {
        return accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    /** 当前用户全部账本在指定自然月的交易（跨账本聚合，按时间倒序）。 */
    @Transactional(readOnly = true)
    public List<Transaction> allTransactionsInMonth(Long userId, YearMonth month) {
        List<Long> ledgerIds = ledgerIds(userId);
        if (ledgerIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        return transactionRepository
                .findByLedgerIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        ledgerIds, from, to);
    }

    private List<Long> ledgerIds(Long userId) {
        // 「全部」聚合覆盖用户可访问的账本（自己拥有的 + 已加入的协作账本），
        // 但**排除 AA 账本**：AA 支出语义为「个人消费份额」、应收/应付为债权债务，
        // 与家庭/个人合计口径不同，不纳入「全部账本」聚合与家庭/个人报表
        // （需求 7.4、10.3；design.md Property 6 特性隔离）。
        List<Long> memberLedgerIds = memberRepository.findByUserId(userId).stream()
                .map(com.damien.youyu.domain.LedgerMember::getLedgerId)
                .toList();
        if (memberLedgerIds.isEmpty()) {
            return List.of();
        }
        return ledgerRepository.findAllById(memberLedgerIds).stream()
                .filter(l -> !l.isAa())
                .map(Ledger::getId)
                .toList();
    }
}
