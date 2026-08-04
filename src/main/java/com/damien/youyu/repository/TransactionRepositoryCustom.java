package com.damien.youyu.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link TransactionRepository} 的自定义读片段：承载那条「必须以 {@link LocalDate} 逐字回读日期、
 * 零时区换算」的追补窗口查询（需求 4.6、4.16）。
 *
 * <p><b>为什么单拎出来走 {@code JdbcTemplate} 而非 Spring Data 的 {@code @Query}</b>：
 * {@code SELECT CAST(created_at AS DATE)} 的原生标量若交给 Spring Data 映射，Hibernate 会依
 * {@code ResultSetMetaData} 把它读成 {@code java.sql.Date}——而 {@code ResultSet#getDate} 走旧式
 * {@code Calendar}、取 JVM 默认时区，非 {@code Asia/Shanghai} 时整日平移（NY 下 {@code 2024-02-29}
 * 读成 {@code 2024-02-28}），且没有 {@code java.sql.Date → LocalDate} 的转换器。改用
 * {@code JdbcTemplate} + {@code ResultSet#getObject(idx, LocalDate.class)}（JDBC 4.2）逐字取日期、
 * 零时区换算，与写侧 {@code hibernate.type.java_time_use_direct_jdbc=true}（挂钟值逐字落库）配成
 * 一对，让整条 {@code LocalDateTime → DATETIME → CAST AS DATE → LocalDate} 往返都不读默认时区。</p>
 */
public interface TransactionRepositoryCustom {

    /**
     * 追补起点（需求 4.6 查询 A）：该用户有效记账交易中最早的 {@code created_at}；{@code lowerBound}
     * 非空时只看 {@code created_at >= lowerBound} 的行，无匹配行时返回 {@code null}。
     *
     * <p>与 {@link #findRecordDatesInWindow} 同理走 {@code JdbcTemplate} + {@code getObject(LocalDateTime.class)}
     * 逐字回读 {@code DATETIME}：原生 {@code @Query} 标量会把它读成经 {@code ResultSet#getTimestamp} 默认时区
     * 换算的 {@code java.sql.Timestamp} 再转 {@link LocalDateTime}，非 {@code Asia/Shanghai} 时整体平移，
     * 会让追补起点错位（UTC+14 下把 {@code 00:00} 的起点推到次日，窗口起点越过唯一那笔交易致漏补，
     * 需求 4.16）。</p>
     */
    LocalDateTime findEarliestRecordCreatedAt(Long userId, LocalDateTime lowerBound);

    /**
     * 追补窗口内的记账日集合（需求 4.6 查询 B）：{@code created_at ∈ [windowStart, windowEndExclusive)}
     * 的有效记账交易的 distinct 记账日，按日期升序返回。窗口跨度由调用方限定为至多 1000 天，
     * 故返回行数 ≤1000、两端都有界。日期以 {@link LocalDate} 逐字回读，零时区换算。
     */
    List<LocalDate> findRecordDatesInWindow(Long userId, LocalDateTime windowStart, LocalDateTime windowEndExclusive);
}
