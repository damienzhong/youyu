package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link CategoryService#seedDefaultsIfEmpty} 的示例测试：空账本补齐默认分类、非空幂等。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategorySeedTest {

    private static final long LEDGER = 100L;

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;

    private CategoryService service() {
        return new CategoryService(categoryRepository, transactionRepository,
                Clock.fixed(Instant.parse("2025-06-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void seedsWhenEmpty_thenIdempotent() {
        CategoryService svc = service();
        int expected = CategoryService.DEFAULT_EXPENSE.length + CategoryService.DEFAULT_INCOME.length;

        svc.seedDefaultsIfEmpty(LEDGER);
        assertThat(categoryRepository.countByLedgerId(LEDGER)).isEqualTo(expected);

        // 再次调用不重复种子。
        svc.seedDefaultsIfEmpty(LEDGER);
        assertThat(categoryRepository.countByLedgerId(LEDGER)).isEqualTo(expected);

        // 存在支出与收入两类。
        assertThat(categoryRepository.findByLedgerIdAndKind(LEDGER, CategoryKind.EXPENSE))
                .hasSize(CategoryService.DEFAULT_EXPENSE.length);
        assertThat(categoryRepository.findByLedgerIdAndKind(LEDGER, CategoryKind.INCOME))
                .hasSize(CategoryService.DEFAULT_INCOME.length);
    }

    @Test
    void doesNotSeedWhenNonEmpty() {
        CategoryService svc = service();
        svc.create(LEDGER, "EXPENSE", "餐饮", null);
        svc.seedDefaultsIfEmpty(LEDGER);
        assertThat(categoryRepository.countByLedgerId(LEDGER)).isEqualTo(1);
    }
}
