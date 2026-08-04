package com.damien.youyu.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link TransactionRepositoryCustom} 的实现：走 {@link JdbcTemplate}，用
 * {@code ResultSet#getObject(idx, LocalDate.class)} 逐字回读 {@code CAST(created_at AS DATE)} 的日期，
 * 零时区换算（需求 4.16）。命名遵循 Spring Data 约定（仓储名 + {@code Impl}），由 Spring Data
 * 自动织入 {@link TransactionRepository}。
 *
 * <p><b>刻意手写 SQL 与 {@code deleted_at IS NULL}</b>：与其它成长体系原生查询一致——{@code Transaction}
 * 带 {@code @SQLRestriction("deleted_at is null")} 只对 JPA 路径生效，这里走原生 JDBC，必须自己写
 * 软删过滤，漏写会把回收站记录算进记账日历。</p>
 *
 * <p>{@code windowStart} / {@code windowEndExclusive} 为 {@link LocalDateTime}，JdbcTemplate 经
 * JDBC 4.2 的 {@code setObject} 逐字绑定为 {@code DATETIME} 边界，同样不读默认时区，与写侧口径一致。</p>
 */
@Repository
public class TransactionRepositoryImpl implements TransactionRepositoryCustom {

    private static final String FIND_EARLIEST_BASE =
            "SELECT MIN(created_at) FROM transactions WHERE created_by = ? "
                    + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL";

    private static final String FIND_RECORD_DATES_IN_WINDOW =
            "SELECT DISTINCT CAST(created_at AS DATE) FROM transactions WHERE created_by = ? "
                    + "AND deleted_at IS NULL AND type IN ('expense','income') AND ledger_id IS NOT NULL "
                    + "AND created_at >= ? AND created_at < ? "
                    + "ORDER BY 1 ASC";

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LocalDateTime findEarliestRecordCreatedAt(Long userId, LocalDateTime lowerBound) {
        // lowerBound 为 null 时省去时间下界（对应 last_record_date 为空、首次追补）；否则只看其之后的交易。
        // 时间列以 getObject(LocalDateTime.class) 逐字回读，零时区换算（需求 4.16）。
        if (lowerBound == null) {
            return jdbcTemplate.queryForObject(FIND_EARLIEST_BASE, LocalDateTime.class, userId);
        }
        return jdbcTemplate.queryForObject(FIND_EARLIEST_BASE + " AND created_at >= ?",
                LocalDateTime.class, userId, lowerBound);
    }

    @Override
    public List<LocalDate> findRecordDatesInWindow(Long userId, LocalDateTime windowStart,
                                                   LocalDateTime windowEndExclusive) {
        return jdbcTemplate.query(FIND_RECORD_DATES_IN_WINDOW,
                (rs, rowNum) -> rs.getObject(1, LocalDate.class),
                userId, windowStart, windowEndExclusive);
    }
}
