package com.damien.youyu.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 迁移目录的静态检查（不需要 Spring 上下文、不连数据库）。
 *
 * <p>三件事：
 * <ol>
 *   <li>recurring-transactions 的新脚本 {@code V38__recurring_transactions.sql} 存在，且版本号严格大于目录内其余全部版本号；</li>
 *   <li>目录内版本号无重复（Flyway 遇重复版本号会直接启动失败）；</li>
 *   <li>历史迁移文件未被改动 —— 以基线清单
 *       {@code src/test/resources/db/migration-baseline.sha256}（文件名 + sha-256）比对。</li>
 * </ol>
 *
 * <p>「未被改动」用校验和清单而不是仅比对文件名：改内容、改注释、改一行 DDL 都不会改名，
 * 只有校验和能抓住。历史迁移一旦执行过就不可再改（Flyway 的 checksum 校验会让已部署环境启动失败），
 * 因此基线清单只应在刻意变更历史脚本时才更新，且这种更新必然出现在 diff 里、逃不过 review。
 *
 * <p>新脚本落地后即随基线一同纳管：本类每次被新 spec 复用时，只需把上一轮的新脚本连同本轮的新脚本
 * 补进基线清单，并把 {@link #NEW_MIGRATION} 指向本轮的新脚本。
 *
 * <p>本轮（offline-sync）即照此办理：既有的 {@code V38__recurring_transactions.sql}、
 * {@code V40__fix_recurring_month_end_bit.sql} 与本轮的 {@code V41__transaction_client_token.sql}
 * 同在基线清单内，{@link #NEW_MIGRATION} 指向 V41。
 *
 * <p>Validates: Requirements 9.1
 */
class MigrationDirectoryTest {

    /**
     * 本 spec 新增的迁移脚本（offline-sync 定为 V41；撰写设计时目录内最大为 V40 即
     * {@code V40__fix_recurring_month_end_bit.sql}）。
     */
    private static final String NEW_MIGRATION = "V42__user_gender_avatar_color.sql";
    private static final int NEW_MIGRATION_VERSION = 42;

    private static final String BASELINE_RESOURCE = "/db/migration-baseline.sha256";
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    private static Path migrationDir() {
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        assertThat(dir)
                .as("迁移目录须存在（测试的工作目录应为项目根目录）")
                .isDirectory();
        return dir;
    }

    private static List<String> migrationFileNames() {
        try (Stream<Path> files = Files.list(migrationDir())) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
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

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读取基线清单：文件名 -> sha-256，保留声明顺序便于失败信息定位。 */
    private static Map<String, String> baseline() {
        Map<String, String> expected = new LinkedHashMap<>();
        try (InputStream in = MigrationDirectoryTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertThat(in).as("基线清单 %s 须存在于测试 classpath", BASELINE_RESOURCE).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                assertThat(parts).as("基线清单每行须为「文件名 sha256」：%s", line).hasSize(2);
                expected.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(expected).as("基线清单不应为空").isNotEmpty();
        return expected;
    }

    @Test
    void newMigrationExists_andHasHighestVersion() {
        List<String> names = migrationFileNames();

        assertThat(names)
                .as("recurring-transactions 的迁移脚本须存在")
                .contains(NEW_MIGRATION);
        assertThat(versionOf(NEW_MIGRATION)).isEqualTo(NEW_MIGRATION_VERSION);

        List<Integer> otherVersions = names.stream()
                .filter(name -> !name.equals(NEW_MIGRATION))
                .map(MigrationDirectoryTest::versionOf)
                .toList();

        assertThat(otherVersions)
                .as("新脚本版本号须严格大于目录内全部既有版本号（不得与任何脚本同号，含其它 spec 预占的版本）")
                .allSatisfy(v -> assertThat(v).isLessThan(NEW_MIGRATION_VERSION));
    }

    @Test
    void migrationVersionsAreUnique() {
        List<String> names = migrationFileNames();

        List<Integer> versions = names.stream().map(MigrationDirectoryTest::versionOf).toList();

        assertThat(versions)
                .as("迁移目录内版本号不得重复（Flyway 遇重复版本号会启动失败）：%s", names)
                .doesNotHaveDuplicates();
    }

    @Test
    void historicalMigrationsAreUnchanged() {
        Path dir = migrationDir();
        Map<String, String> expected = baseline();
        List<String> names = migrationFileNames();

        // 新脚本一并纳入基线：此后任何对它的改动都必须显式更新基线清单、逃不过 review
        assertThat(expected)
                .as("本 spec 新增的迁移脚本须纳入基线清单 %s", BASELINE_RESOURCE)
                .containsKey(NEW_MIGRATION);

        // 基线里的文件既不能消失也不能改名
        assertThat(names)
                .as("历史迁移文件不得被删除或重命名")
                .containsAll(expected.keySet());

        List<String> modified = new ArrayList<>();
        expected.forEach((name, hash) -> {
            String actual = sha256(dir.resolve(name));
            if (!actual.equals(hash)) {
                modified.add(name + "（基线 " + hash + " → 实际 " + actual + "）");
            }
        });

        assertThat(modified)
                .as("历史迁移文件内容不得被修改；若确需变更，须同步更新 %s", BASELINE_RESOURCE)
                .isEmpty();

        // 基线之外的文件只允许是版本号更大的后续脚本（后续 spec 不得插到已纳管版本之前）
        assertThat(names)
                .filteredOn(name -> !expected.containsKey(name))
                .as("新增迁移文件的版本号须大于基线中的全部版本号")
                .allSatisfy(name -> assertThat(versionOf(name))
                        .isGreaterThan(expected.keySet().stream()
                                .mapToInt(MigrationDirectoryTest::versionOf)
                                .max()
                                .orElseThrow()));
    }
}
