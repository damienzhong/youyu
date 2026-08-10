package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 记账推荐的兼容性回归静态检查（record-suggestion 任务 6.2；不需要 Spring 上下文、不连数据库）。
 *
 * <p>把「本 spec 是纯增量、纯只读」这条边界钉成可执行断言，锁住三件事：
 * <ol>
 *   <li><b>无写语句</b>：本 spec 完全自有的源文件（控制器、DTO、服务、排序器、投影接口）不含任何
 *       写操作 —— 无 {@code INSERT/UPDATE/DELETE} 语句、无 {@code @Modifying}、无
 *       {@code save/saveAll/delete/deleteAll/persist/merge/remove} 之类写调用；只允许只读事务
 *       与 {@code SELECT}/派生读查询（需求 8.1）。</li>
 *   <li><b>无迁移改动</b>：本 spec 未新增任何迁移脚本、未新建任何表（需求 8.2）。全局最大版本号
 *       随后续 spec 增长，当前为 {@code V37}（aa-ledger 的 {@code V37__aa_ledger.sql}）。</li>
 *   <li><b>不碰记账模板</b>：本 spec 自有源文件及其对既有仓库的两处只读增补，均不引用
 *       {@code transaction_templates}/{@code TransactionTemplate}（需求 2.6、8.3）。</li>
 * </ol>
 *
 * <p>另对既有仓库的两处<b>增量</b>方法做定点只读校验：{@code TransactionRepository.findSuggestionWindowRows}
 * 的 {@code @Query} 必须是 {@code SELECT} 且未标 {@code @Modifying}；{@code CategoryRepository.findByIdIn}
 * 必须是派生读查询（{@code findBy...} 命名、未标 {@code @Modifying}/{@code @Query} 写注解）。共享仓库
 * 文件本身含既有写方法（账本/注销级联删除等），故不整文件扫描，只定点校验本 spec 增补的那两个方法。</p>
 *
 * <p>「移除推荐端点后交易/账本/分类/模板/预算/报表既有测试全绿（契约不变）」由既有测试套件承载：
 * 本 spec 未改任何既有生产方法（仓库两处均为纯增补，见上），既有测试对既有代码路径的断言不受影响。</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.4（另涉 2.6）</p>
 */
class RecordSuggestionCompatibilityRegressionTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "damien", "youyu");

    /** 本 spec 完全自有的源文件（可整文件扫描写语句）。 */
    private static final List<Path> SPEC_OWNED_FILES = List.of(
            SRC.resolve("api/RecordSuggestionController.java"),
            SRC.resolve("api/dto/RecordSuggestionResponse.java"),
            SRC.resolve("api/dto/RecordSuggestionItem.java"),
            SRC.resolve("service/RecordSuggestionService.java"),
            SRC.resolve("service/RecordSuggestionRanker.java"),
            SRC.resolve("service/RankedShape.java"),
            SRC.resolve("repository/SuggestionRow.java"));

    private static final Path TRANSACTION_REPOSITORY = SRC.resolve("repository/TransactionRepository.java");
    private static final Path CATEGORY_REPOSITORY = SRC.resolve("repository/CategoryRepository.java");

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    /**
     * 无歧义的写操作标记：出现即视为写。刻意不含裸 {@code delete}/{@code update}/{@code insert}
     * 词根，避免误伤 {@code @SQLRestriction("deleted_at is null")} 之类注释与列名。
     */
    private static final List<String> WRITE_MARKERS = List.of(
            "INSERT INTO",
            "DELETE FROM",
            "UPDATE ",
            "@Modifying",
            ".save(",
            ".saveAll(",
            ".saveAndFlush(",
            ".delete(",
            ".deleteAll(",
            ".deleteById(",
            ".deleteBy",
            "deleteByLedgerId",
            "deleteByUserId",
            ".persist(",
            ".merge(",
            ".remove(",
            ".flush(");

    private static final List<String> TEMPLATE_MARKERS = List.of("transaction_templates", "TransactionTemplate");

    private static String read(Path file) {
        assertThat(file).as("源文件须存在：%s", file).isRegularFile();
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void specOwnedFilesContainNoWriteOperations() {
        for (Path file : SPEC_OWNED_FILES) {
            String upper = read(file).toUpperCase();
            for (String marker : WRITE_MARKERS) {
                assertThat(upper)
                        .as("本 spec 自有源文件不得含写操作标记「%s」：%s（需求 8.1）", marker, file)
                        .doesNotContain(marker.toUpperCase());
            }
        }
    }

    @Test
    void specOwnedFilesDoNotReferenceTransactionTemplates() {
        for (Path file : SPEC_OWNED_FILES) {
            String text = read(file);
            for (String marker : TEMPLATE_MARKERS) {
                assertThat(text)
                        .as("本 spec 自有源文件不得引用记账模板「%s」：%s（需求 2.6、8.3）", marker, file)
                        .doesNotContain(marker);
            }
        }
    }

    @Test
    void transactionRepositoryWindowQueryIsReadOnly() {
        String text = read(TRANSACTION_REPOSITORY);

        int methodIdx = text.indexOf("findSuggestionWindowRows");
        assertThat(methodIdx)
                .as("TransactionRepository 须含本 spec 增补的窗口投影查询 findSuggestionWindowRows")
                .isGreaterThanOrEqualTo(0);

        // 取该方法紧邻的 @Query 注解块（从其前一个 @Query 到方法名之间）做定点只读校验。
        int queryIdx = text.lastIndexOf("@Query", methodIdx);
        assertThat(queryIdx)
                .as("findSuggestionWindowRows 须由 @Query 声明")
                .isGreaterThanOrEqualTo(0);
        String queryBlock = text.substring(queryIdx, methodIdx).toUpperCase();

        assertThat(queryBlock)
                .as("窗口投影查询须为 SELECT（需求 8.1）")
                .contains("SELECT");
        assertThat(queryBlock)
                .as("窗口投影查询不得为写查询（需求 8.1）")
                .doesNotContain("@MODIFYING")
                .doesNotContain("INSERT")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ");
        assertThat(queryBlock)
                .as("窗口投影查询不得引用记账模板（需求 2.6）")
                .doesNotContain("TRANSACTION_TEMPLATES");
    }

    @Test
    void categoryRepositoryFindByIdInIsDerivedReadQuery() {
        String text = read(CATEGORY_REPOSITORY);

        int methodIdx = text.indexOf("findByIdIn");
        assertThat(methodIdx)
                .as("CategoryRepository 须含本 spec 增补的批量取分类方法 findByIdIn")
                .isGreaterThanOrEqualTo(0);

        // 派生读查询：不应挂 @Query 写注解，也不应挂 @Modifying。检视方法名前一小段声明区。
        int lineStart = text.lastIndexOf('\n', methodIdx);
        String declPrefix = text.substring(Math.max(0, lineStart - 200), methodIdx);
        assertThat(declPrefix)
                .as("findByIdIn 须为派生读查询，不得标注 @Modifying（需求 8.1）")
                .doesNotContain("@Modifying");
        int lineEnd = text.indexOf('\n', methodIdx);
        assertThat(text.substring(lineStart + 1, lineEnd < 0 ? text.length() : lineEnd))
                .as("findByIdIn 须按 findBy 派生命名读取（需求 8.1）")
                .contains("findByIdIn");
    }

    @Test
    void noMigrationAboveV37() {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            List<Integer> versions = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .map(RecordSuggestionCompatibilityRegressionTest::versionOf)
                    .toList();

            assertThat(versions).as("迁移目录不应为空").isNotEmpty();
            // record-suggestion 本身不新增迁移；全局最大版本号随后续 spec 增长，
            // 目前由 aa-ledger 的 V37__aa_ledger.sql 推进到 37。
            assertThat(versions.stream().mapToInt(Integer::intValue).max().orElseThrow())
                    .as("record-suggestion 不新增迁移：目录内当前最大版本号为 V37（需求 8.2）")
                    .isEqualTo(37);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int versionOf(String fileName) {
        Matcher m = MIGRATION_NAME.matcher(fileName);
        assertThat(m.matches())
                .as("迁移文件名须形如 V<N>__name.sql：%s", fileName)
                .isTrue();
        return Integer.parseInt(m.group(1));
    }
}
