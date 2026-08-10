package com.damien.youyu.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@code V38__recurring_transactions.sql} 迁移冒烟测试（静态结构校验，不需要 Spring 上下文、不连数据库）。
 *
 * <p>dev/test 采用 H2 + Hibernate {@code ddl-auto}（测试环境禁用 Flyway），实体映射由实体测试覆盖；
 * 而 V38 本体为 MySQL 方言脚本，无需一套 live MySQL 即可对其做健壮的文本结构断言：
 * <ol>
 *   <li>两张新表 {@code recurring_rules} / {@code recurring_pending_items} 均被 CREATE；</li>
 *   <li>{@code recurring_pending_items} 声明唯一约束 {@code uk_recurring_pending_rule_date (rule_id, occurrence_date)}；</li>
 *   <li>设计文档所列索引齐备；金额列为 {@code DECIMAL(18,2)}；</li>
 *   <li><b>无指向既有表的外键</b>：脚本不含任何 {@code FOREIGN KEY} / {@code REFERENCES} 子句（需求 9.2，裸 id 列、可摘除）；</li>
 *   <li><b>纯增量</b>：脚本不含 {@code ALTER TABLE}，只有两条 {@code CREATE TABLE}（不触碰既有表结构）；</li>
 *   <li>V38 存在且为目录内唯一的 V38 文件。</li>
 * </ol>
 *
 * <p>历史脚本未被改动、版本号唯一且严格最高由 {@link MigrationDirectoryTest} 以基线 sha256 保证，本类不重复。
 *
 * <p>Validates: Requirements 9.1, 9.2
 */
class RecurringMigrationSmokeTest {

    private static final String V38_NAME = "V38__recurring_transactions.sql";

    private static Path migrationDir() {
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        assertThat(dir)
                .as("迁移目录须存在（测试的工作目录应为项目根目录）")
                .isDirectory();
        return dir;
    }

    private static String scriptText() {
        try {
            return Files.readString(migrationDir().resolve(V38_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 去掉行注释（-- ...）后的脚本正文，避免注释里的措辞干扰结构断言。 */
    private static String scriptWithoutComments() {
        return scriptText().lines()
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx >= 0 ? line.substring(0, idx) : line;
                })
                .reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append)
                .toString();
    }

    @Test
    void v38Exists_andIsTheOnlyV38() {
        try (Stream<Path> files = Files.list(migrationDir())) {
            List<String> v38Files = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("V38__") && name.endsWith(".sql"))
                    .sorted()
                    .toList();

            assertThat(v38Files)
                    .as("V38 迁移脚本须存在且唯一")
                    .containsExactly(V38_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void createsBothNewTables() {
        String sql = scriptWithoutComments();

        assertThat(sql)
                .as("须 CREATE TABLE recurring_rules")
                .containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+recurring_rules"));
        assertThat(sql)
                .as("须 CREATE TABLE recurring_pending_items")
                .containsPattern(Pattern.compile("(?i)CREATE\\s+TABLE\\s+recurring_pending_items"));
    }

    @Test
    void declaresUniqueConstraintOnRuleAndOccurrenceDate() {
        String sql = scriptWithoutComments();

        // uk_recurring_pending_rule_date UNIQUE (rule_id, occurrence_date)
        assertThat(sql)
                .as("须声明唯一约束 uk_recurring_pending_rule_date 于 (rule_id, occurrence_date)")
                .containsPattern(Pattern.compile(
                        "(?is)uk_recurring_pending_rule_date\\s+UNIQUE\\s*\\(\\s*rule_id\\s*,\\s*occurrence_date\\s*\\)"));
    }

    @Test
    void declaresDocumentedIndexes() {
        String sql = scriptWithoutComments();

        assertThat(sql)
                .as("recurring_rules 须声明索引 idx_recurring_rules_ledger_status (ledger_id, status)")
                .containsPattern(Pattern.compile(
                        "(?is)idx_recurring_rules_ledger_status\\s*\\(\\s*ledger_id\\s*,\\s*status\\s*\\)"));
        assertThat(sql)
                .as("recurring_rules 须声明索引 idx_recurring_rules_user (user_id)")
                .containsPattern(Pattern.compile(
                        "(?is)idx_recurring_rules_user\\s*\\(\\s*user_id\\s*\\)"));
        assertThat(sql)
                .as("recurring_pending_items 须声明索引 idx_recurring_pending_ledger_status_date (ledger_id, status, occurrence_date)")
                .containsPattern(Pattern.compile(
                        "(?is)idx_recurring_pending_ledger_status_date\\s*\\(\\s*ledger_id\\s*,\\s*status\\s*,\\s*occurrence_date\\s*\\)"));
        assertThat(sql)
                .as("recurring_pending_items 须声明索引 idx_recurring_pending_rule (rule_id)")
                .containsPattern(Pattern.compile(
                        "(?is)idx_recurring_pending_rule\\s*\\(\\s*rule_id\\s*\\)"));
    }

    @Test
    void amountColumnsAreDecimal18_2() {
        String sql = scriptWithoutComments();

        // 两张表的 amount 列都应为 DECIMAL(18,2)
        assertThat(sql)
                .as("amount 列须为 DECIMAL(18,2)")
                .containsPattern(Pattern.compile("(?i)amount\\s+DECIMAL\\s*\\(\\s*18\\s*,\\s*2\\s*\\)"));

        // 且不得出现其它精度的 DECIMAL，确保金额口径统一
        Matcher decimals = Pattern.compile("(?i)DECIMAL\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").matcher(sql);
        while (decimals.find()) {
            assertThat(decimals.group(1)).as("DECIMAL 精度须为 18").isEqualTo("18");
            assertThat(decimals.group(2)).as("DECIMAL 标度须为 2").isEqualTo("2");
        }
    }

    /** 需求 9.2：无指向既有表的外键——脚本不含任何 FOREIGN KEY / REFERENCES 子句（裸 id 列，可整块摘除）。 */
    @Test
    void hasNoForeignKeysIntoExistingTables() {
        String sql = scriptWithoutComments().toUpperCase(Locale.ROOT);

        assertThat(sql)
                .as("不得声明 FOREIGN KEY（需求 9.2：新表以裸 id 列引用，不建外键）")
                .doesNotContain("FOREIGN KEY");
        assertThat(sql)
                .as("不得含 REFERENCES 子句（需求 9.2：不建指向既有表的外键）")
                .doesNotContainPattern(Pattern.compile("\\bREFERENCES\\b"));
    }

    /** 需求 9.2：纯增量——不改任何既有表，脚本无 ALTER TABLE，仅两条 CREATE TABLE。 */
    @Test
    void isPurelyAdditive_noAlterTable_onlyTwoCreateTables() {
        String sql = scriptWithoutComments();

        assertThat(sql.toUpperCase(Locale.ROOT))
                .as("不得含 ALTER TABLE（需求 9.2：不对既有表加列 / 加约束）")
                .doesNotContain("ALTER TABLE");

        long createTableCount = Pattern.compile("(?i)CREATE\\s+TABLE").matcher(sql).results().count();
        assertThat(createTableCount)
                .as("脚本须为纯增量：恰好两条 CREATE TABLE（recurring_rules、recurring_pending_items）")
                .isEqualTo(2);

        // 不得出现 DROP / TRUNCATE 等触碰既有结构 / 数据的语句
        assertThat(sql.toUpperCase(Locale.ROOT))
                .as("不得含 DROP TABLE")
                .doesNotContain("DROP TABLE");
        assertThat(sql.toUpperCase(Locale.ROOT))
                .as("不得含 TRUNCATE")
                .doesNotContain("TRUNCATE");
    }
}
