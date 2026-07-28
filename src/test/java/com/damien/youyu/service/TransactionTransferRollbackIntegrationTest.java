package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 转账「更新中途失败回滚」的集成测试（关联需求 4.10）。
 *
 * <p>与切片属性/单元测试不同，本测试<b>不</b>使用测试级事务包裹，而是通过完整 Spring 上下文
 * 注入真实的 {@link TransactionService} Bean，使其 {@code @Transactional} 方法拥有<b>真实的事务边界</b>
 * （真正提交或回滚到 H2），从而能在数据库层面验证「整事务回滚」。</p>
 *
 * <p>故障注入采用一个<b>真实的</b>中途失败场景：转账目标账户余额本已处于列精度上界
 * （{@code DECIMAL(18,2)} 的 9,999,999,999,999,999.99），再叠加一笔合法的大额转账会使目标账户
 * 余额超出列精度，在事务提交/刷库阶段触发数据库层异常。此时源账户扣减已在同一事务内施加，
 * 若无回滚将造成「源账户已扣、目标账户未增」的部分变更。断言：抛出异常、<b>无</b> Transaction 落库、
 * 源账户与目标账户 {@code current_balance} 均保持不变（完整回滚，需求 4.10）。</p>
 */
@SpringBootTest
class TransactionTransferRollbackIntegrationTest {

    /** DECIMAL(18,2) 允许的最大值（同时也是交易金额上限）。 */
    private static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private Clock clock;

    private Account persistAccount(long ledgerId, String name, BigDecimal balance) {
        LocalDateTime now = LocalDateTime.now(clock);
        Account a = new Account();
        a.setUserId(ledgerId);
        a.setName(name);
        a.setType(AccountType.CASH);
        a.setInitialBalance(balance);
        a.setCurrentBalance(balance);
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    @Test
    void transferMidUpdateFailure_rollsBackEntirely_noRowNoBalanceChange() {
        long ledgerId = 900_000_001L;
        // 源账户先创建（较小 id，回滚前会先被扣减）；目标账户余额已达列精度上界。
        Account source = persistAccount(ledgerId, "源账户", new BigDecimal("0.00"));
        Account dest = persistAccount(ledgerId, "目标账户", MAX);

        BigDecimal sourceBefore = source.getCurrentBalance();
        BigDecimal destBefore = dest.getCurrentBalance();

        // 合法的大额转账：金额在允许范围内，但会使目标账户余额溢出 DECIMAL(18,2) 列精度，
        // 在提交/刷库阶段触发数据库异常（真实的转账中途失败）。
        Throwable thrown = catchThrowable(() -> transactionService.create(
                ledgerId, ledgerId, "transfer", MAX, null, null,
                source.getId(), dest.getId(), null, "溢出触发回滚"));

        // 需求 4.10：转账中途失败 → 抛出异常。
        assertThat(thrown).isNotNull();

        // 需求 4.10：整事务回滚 —— 不创建任何 Transaction。
        assertThat(transactionRepository.findByLedgerId(ledgerId)).isEmpty();

        // 需求 4.10：源账户扣减被回滚，目标账户未变，两账户余额均保持不变。
        Account sourceAfter = accountRepository.findByIdAndUserId(source.getId(), ledgerId).orElseThrow();
        Account destAfter = accountRepository.findByIdAndUserId(dest.getId(), ledgerId).orElseThrow();
        assertThat(sourceAfter.getCurrentBalance()).isEqualByComparingTo(sourceBefore);
        assertThat(destAfter.getCurrentBalance()).isEqualByComparingTo(destBefore);
    }
}
