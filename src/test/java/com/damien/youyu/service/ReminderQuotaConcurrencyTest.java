package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.repository.ReminderQuotaRepository;

/**
 * {@code reminder_quota} 并发上报授权<b>不丢更新</b>的服务层测试（任务 4.4，关联需求 5.8）。
 *
 * <p>需求 5.8 要求剩余订阅次数的每次增减执行原子更新，并发的上报授权与发送扣减不产生丢失更新
 * （终值等于所有增减操作的净和）。这条只有在<b>真实提交的多事务</b>下才能观察到——
 * {@link ReminderService#grantQuota} 带 {@code @Transactional}，每个线程各自开一个事务提交，
 * 故本类刻意<b>不用测试级事务包裹</b>（{@code @SpringBootTest} 默认不开事务），并用全局自增
 * {@link #SEQ} 给每次迭代分配互不相同的 {@code userId}，迭代间天然互不影响。</p>
 *
 * <p>{@link ReminderQuotaRepository#addCapped} 是单条 {@code INSERT ... ON DUPLICATE KEY UPDATE
 * remaining = LEAST(remaining + delta, 50)}，在库内一次性完成读改写。若它不是原子的（走「先查后写」），
 * N 个并发 {@code +1} 会互相覆盖，终值 &lt; N；本测试以 40（&lt; 上限 50，不触发夹取）个并发上报断言
 * 终值恰为 40，从而把丢失更新钉死。</p>
 *
 * <p>并发编排对齐 {@code StreakConcurrentTerminalPropertyTest}：倒计时门让全部线程尽量同时起跑。</p>
 *
 * <p>Feature: custom-reminder。Validates: Requirements 5.8。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-reminder-quota-concurrent;DB_CLOSE_DELAY=-1;MODE=MySQL")
class ReminderQuotaConcurrencyTest {

    /** 全局自增序号：保证跨迭代的用户 id 全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong(1_360_000_000L);

    @Autowired private ReminderService service;
    @Autowired private ReminderQuotaRepository quotaRepository;

    /** 40 个线程各上报 1 次授权（40 &lt; 上限 50）：终值恰为 40，证明原子累加不丢更新。 */
    @Test
    void concurrentGrants_doNotLoseUpdates() throws InterruptedException {
        long userId = SEQ.getAndIncrement();
        int concurrency = 40;

        runConcurrently(concurrency, () -> service.grantQuota(userId, "1"));

        assertThat(quotaRepository.findRemaining(userId))
                .as("40 次并发 +1 的净和应为 40（原子 addCapped 不丢更新）")
                .contains(concurrency);
    }

    /** 并发累加越过上限 50 时收敛到 50，且不出现丢更新导致的偏低值（需求 5.3、5.8）。 */
    @Test
    void concurrentGrants_capAtFifty_withoutLostUpdates() throws InterruptedException {
        long userId = SEQ.getAndIncrement();
        // 20 个线程各上报 5 次授权 = 100，远超上限 50 → 终值恰为 50。
        int concurrency = 20;

        runConcurrently(concurrency, () -> service.grantQuota(userId, "5"));

        assertThat(quotaRepository.findRemaining(userId))
                .as("累加越过上限收敛到 50")
                .contains(50);
    }

    /**
     * 用一道倒计时门让 {@code concurrency} 个线程尽量同时起跑，并在 5 秒内全部落定。
     */
    private void runConcurrently(int concurrency, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS))
                    .as("全部并发上报应在 5 秒内落定").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
