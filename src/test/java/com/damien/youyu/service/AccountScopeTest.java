package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link AccountService} 按 {@link AccountScope} 分域的隔离测试：
 * 独立账本(用户级, ledger_id 空) 与协作账本(账本级, ledger_id) 两个账户池互不可见，
 * 且跨作用域访问单个账户返回 NOT_FOUND。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountScopeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final long USER = 1L;
    private static final long LEDGER = 100L;

    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private AccountService service() {
        return new AccountService(accountRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    @Test
    void independentAndCollaborativePools_areIsolated() {
        AccountService svc = service();
        AccountScope indep = AccountScope.independent(USER);
        AccountScope collab = AccountScope.collaborative(LEDGER, USER);

        Account personal = svc.create(indep, "个人现金", "CASH", new BigDecimal("100.00"), 0);
        Account shared = svc.create(collab, "公共钱包", "CASH", new BigDecimal("0.00"), 0);

        // 归属正确：独立账本账户 ledger_id 为空；协作账本账户 ledger_id = 账本。
        assertThat(personal.getLedgerId()).isNull();
        assertThat(personal.getUserId()).isEqualTo(USER);
        assertThat(shared.getLedgerId()).isEqualTo(LEDGER);

        // 列表互不含对方池的账户。
        List<Account> indepList = svc.list(indep);
        List<Account> collabList = svc.list(collab);
        assertThat(indepList).extracting(Account::getId).containsExactly(personal.getId());
        assertThat(collabList).extracting(Account::getId).containsExactly(shared.getId());
    }

    @Test
    void crossScopeSingleAccess_notFound() {
        AccountService svc = service();
        AccountScope indep = AccountScope.independent(USER);
        AccountScope collab = AccountScope.collaborative(LEDGER, USER);

        Account personal = svc.create(indep, "个人现金", "CASH", new BigDecimal("100.00"), 0);
        Account shared = svc.create(collab, "公共钱包", "CASH", new BigDecimal("0.00"), 0);

        // 用协作作用域更新用户级账户 → NOT_FOUND。
        ApiException ex1 = catchThrowableOfType(
                () -> svc.update(collab, personal.getId(), "改名", "CASH"), ApiException.class);
        assertThat(ex1.getCode()).isEqualTo("NOT_FOUND");

        // 用独立作用域删除协作账户 → NOT_FOUND。
        ApiException ex2 = catchThrowableOfType(
                () -> svc.delete(indep, shared.getId()), ApiException.class);
        assertThat(ex2.getCode()).isEqualTo("NOT_FOUND");
    }
}
