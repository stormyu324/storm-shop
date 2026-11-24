package com.javastorm.shop.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 把自带的 H2 数据库（data/shop.mv.db）里的数据搬到 MySQL。
 *
 * <p>由 migrate-to-mysql 脚本设置环境变量 MIGRATE_FROM_H2 后启动商城触发：程序启动时先按实体建好 MySQL 表，
 * 这里再把 H2 的数据复制过去，完成后退出。最先执行，所以不会写入示例商品。H2 只读打开，原数据不变。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "MIGRATE_FROM_H2")
public class H2ToMySqlMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(H2ToMySqlMigration.class);
    /** H2 数据库正被另一个程序独占打开。 */
    private static final int DATABASE_ALREADY_OPEN = 90020;

    private final DataSource dataSource;
    private final ConfigurableApplicationContext context;
    private final String sourcePath;
    private final String sourceUser;
    private final String sourcePassword;

    public H2ToMySqlMigration(DataSource dataSource, ConfigurableApplicationContext context,
                              @Value("${MIGRATE_FROM_H2}") String sourcePath,
                              @Value("${MIGRATE_H2_USERNAME:sa}") String sourceUser,
                              @Value("${MIGRATE_H2_PASSWORD:}") String sourcePassword) {
        this.dataSource = dataSource;
        this.context = context;
        this.sourcePath = sourcePath;
        this.sourceUser = sourceUser;
        this.sourcePassword = sourcePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        int status = 0;
        try {
            migrate();
        } catch (MigrationException e) {
            log.error("\n\n数据迁移失败：{}\n没有写入任何数据，MySQL 和原来的 data 文件夹都没有改动。\n", e.getMessage());
            status = 1;
        } catch (SQLException e) {
            log.error("\n\n数据迁移失败，数据库出错：{}\n没有写入任何数据，MySQL 和原来的 data 文件夹都没有改动。\n", e.getMessage(), e);
            status = 1;
        }
        int exitCode = status;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    void migrate() throws MigrationException, SQLException {
        Path file = Path.of(sourcePath + ".mv.db").toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new MigrationException("没有找到原来的数据库文件 " + file);
        }
        try (Connection target = dataSource.getConnection()) {
            if (!TableCopier.isMySql(target)) {
                throw new MigrationException("目标数据库不是 MySQL：请先在 config.env 里填好 DB_URL、DB_USERNAME、DB_PASSWORD。");
            }
            checkCharset(target);
            log.info("开始迁移：{} → MySQL 数据库 {}", file, target.getCatalog());
            // ACCESS_MODE_DATA=r：只读打开，保证不改动原数据
            String url = "jdbc:h2:file:" + sourcePath + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
            try (Connection source = DriverManager.getConnection(url, sourceUser, sourcePassword)) {
                TableCopier.Report report = new TableCopier().copy(source, target);
                printReport(report);
            } catch (SQLException e) {
                if (e.getErrorCode() == DATABASE_ALREADY_OPEN) {
                    throw new MigrationException("原来的数据库正被另一个程序打开，请关掉所有商城窗口后再迁移。");
                }
                throw e;
            }
        }
    }

    /** 字符集必须是 utf8mb4，否则中文和表情会出错；排序规则建议 utf8mb4_bin，和 H2 一样区分大小写。 */
    private void checkCharset(Connection target) throws SQLException, MigrationException {
        try (Statement statement = target.createStatement();
             ResultSet rs = statement.executeQuery("SELECT @@character_set_database, @@collation_database")) {
            rs.next();
            String charset = rs.getString(1);
            String collation = rs.getString(2);
            if (!"utf8mb4".equalsIgnoreCase(charset)) {
                throw new MigrationException("MySQL 数据库的字符集是 " + charset + "，须为 utf8mb4。请按 README 的语句重新创建数据库。");
            }
            if (!"utf8mb4_bin".equalsIgnoreCase(collation)) {
                log.warn("MySQL 数据库的排序规则是 {}，不区分大小写：SKU、分类名等只差大小写的会被当成重复。"
                        + "建议按 README 用 utf8mb4_bin 创建数据库。", collation);
            }
        }
    }

    private void printReport(TableCopier.Report report) {
        StringBuilder text = new StringBuilder("\n\n数据迁移完成，各表行数：\n");
        long total = 0;
        for (Map.Entry<String, Long> entry : report.rows().entrySet()) {
            text.append(String.format("  %-24s %d%n", entry.getKey(), entry.getValue()));
            total += entry.getValue();
        }
        text.append("合计 ").append(total).append(" 行。\n");
        if (!report.skippedTables().isEmpty() || !report.skippedColumns().isEmpty()) {
            text.append("以下是旧版本留下、现在已不使用的表或列，没有迁移：")
                    .append(String.join("、", report.skippedTables()))
                    .append(report.skippedTables().isEmpty() || report.skippedColumns().isEmpty() ? "" : "、")
                    .append(String.join("、", report.skippedColumns())).append('\n');
        }
        text.append("以后启动商城会直接使用 MySQL。原来的 data 文件夹没有改动，确认无误后可以留作备份。\n");
        log.info(text.toString());
    }
}
