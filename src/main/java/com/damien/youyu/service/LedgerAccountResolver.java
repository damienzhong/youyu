package com.damien.youyu.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;

/**
 * 账本/账户可见性解析器：集中解析"某用户在某账本能用/能看哪些账户、能否看余额、能否用于记账更新余额"，
 * 取代旧的 {@code AccountScope} 隐式分支（关联需求 3、4）。
 *
 * <p>核心判定"账户在账本对用户可用"：账户参与该账本（存在 {@code account_ledger} 行）且
 * （账户 owner 为该用户 或 该行 {@code visible_to_others=true}）。owner 始终能使用自己参与该账本的账户，
 * 不受可见性标志限制；其他成员仅能使用被暴露的账户。</p>
 */
@Service
public class LedgerAccountResolver {

    private final AccountRepository accountRepository;
    private final AccountLedgerRepository accountLedgerRepository;

    public LedgerAccountResolver(
            AccountRepository accountRepository,
            AccountLedgerRepository accountLedgerRepository) {
        this.accountRepository = accountRepository;
        this.accountLedgerRepository = accountLedgerRepository;
    }

    /** 某用户在某账本记账时可选的账户（自己参与的 + 他人暴露的），按 sort_order、id 升序。 */
    @Transactional(readOnly = true)
    public List<Account> selectableAccounts(Long userId, Long ledgerId) {
        return accountLedgerRepository.findSelectableAccounts(userId, ledgerId);
    }

    /**
     * 校验账户在该账本对该用户可用，并加行级悲观写锁返回账户实体，供记账时更新余额。
     * 不可用（未参与账本或未暴露给他人）一律 {@code NOT_FOUND}。
     */
    @Transactional
    public Account lockUsableAccount(Long userId, Long ledgerId, Long accountId) {
        AccountLedger link = accountLedgerRepository.findByAccountIdAndLedgerId(accountId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        Account account = accountRepository.findForUpdateById(accountId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        if (!account.getUserId().equals(userId) && !link.isVisibleToOthers()) {
            throw ApiException.notFound("账户不存在");
        }
        return account;
    }

    /** 该账户在该账本对该 viewer 是否可见（owner 或 visible_to_others）。 */
    @Transactional(readOnly = true)
    public boolean visible(Long viewerUserId, Long ledgerId, Account account) {
        if (account.getUserId().equals(viewerUserId)) {
            return true;
        }
        return accountLedgerRepository.findByAccountIdAndLedgerId(account.getId(), ledgerId)
                .map(AccountLedger::isVisibleToOthers)
                .orElse(false);
    }

    /** 该账户在该账本对该 viewer 是否可见余额（owner 或 show_balance）。 */
    @Transactional(readOnly = true)
    public boolean canSeeBalance(Long viewerUserId, Long ledgerId, Account account) {
        if (account.getUserId().equals(viewerUserId)) {
            return true;
        }
        Optional<AccountLedger> link =
                accountLedgerRepository.findByAccountIdAndLedgerId(account.getId(), ledgerId);
        return link.isPresent() && link.get().isVisibleToOthers() && link.get().isShowBalance();
    }
}
