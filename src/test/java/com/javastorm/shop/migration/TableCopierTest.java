package com.javastorm.shop.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 迁移时的整表复制：用两个内存 H2 库模拟旧库和新库（MySQL 上的完整迁移另行手工验证）。 */
class TableCopierTest {

    private Connection source;
    private Connection target;

    @BeforeEach
    void open() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:source-" + UUID.randomUUID(), "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:target-" + UUID.randomUUID(), "sa", "");
        execute(source, """
                CREATE TABLE CATEGORY (ID BIGINT PRIMARY KEY, NAME VARCHAR(50));
                CREATE TABLE PRODUCT (ID BIGINT PRIMARY KEY, NAME VARCHAR(200), PRICE DECIMAL(12, 2),
                    STATUS ENUM('DRAFT', 'ON_SALE'), ACTIVE BOOLEAN, CREATED_AT TIMESTAMP(6), CATEGORY_ID BIGINT,
                    OLD_FIELD VARCHAR(10), FOREIGN KEY (CATEGORY_ID) REFERENCES CATEGORY (ID));
                CREATE TABLE OBSOLETE_TABLE (ID BIGINT PRIMARY KEY);
                INSERT INTO CATEGORY VALUES (1, '服装');
                INSERT INTO PRODUCT VALUES (7, '纯棉T恤 😀', 59.90, 'ON_SALE', TRUE, TIMESTAMP '2026-11-01 08:30:15.123456', 1, 'x');
                INSERT INTO PRODUCT VALUES (8, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
                INSERT INTO OBSOLETE_TABLE VALUES (1);
                """);
        // 新库：表名小写，外键顺序和旧库无关（先建 product），没有旧版本的表和列
        execute(target, """
                CREATE TABLE "product" ("id" BIGINT PRIMARY KEY, "name" VARCHAR(200), "price" DECIMAL(12, 2),
                    "status" ENUM('DRAFT', 'ON_SALE'), "active" BOOLEAN, "created_at" TIMESTAMP(6), "category_id" BIGINT);
                CREATE TABLE "category" ("id" BIGINT PRIMARY KEY, "name" VARCHAR(50));
                ALTER TABLE "product" ADD FOREIGN KEY ("category_id") REFERENCES "category" ("id");
                """);
    }

    @AfterEach
    void close() throws SQLException {
        source.close();
        target.close();
    }

    @Test
    void copiesEveryValueAndReportsObsoleteParts() throws Exception {
        TableCopier.Report report = new TableCopier().copy(source, target);

        assertThat(report.rows()).containsEntry("category", 1L).containsEntry("product", 2L);
        assertThat(report.skippedTables()).containsExactly("OBSOLETE_TABLE");
        assertThat(report.skippedColumns()).containsExactly("PRODUCT.OLD_FIELD");
        try (Statement statement = target.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM \"product\" ORDER BY \"id\"")) {
            rs.next();
            assertThat(rs.getLong("id")).isEqualTo(7);
            assertThat(rs.getString("name")).isEqualTo("纯棉T恤 😀");
            assertThat(rs.getBigDecimal("price")).isEqualByComparingTo(new BigDecimal("59.90"));
            assertThat(rs.getString("status")).isEqualTo("ON_SALE");
            assertThat(rs.getBoolean("active")).isTrue();
            assertThat(rs.getObject("created_at", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 11, 1, 8, 30, 15, 123_456_000));
            assertThat(rs.getLong("category_id")).isEqualTo(1);
            rs.next();
            assertThat(rs.getLong("id")).isEqualTo(8);
            assertThat(rs.getObject("name")).isNull();
            assertThat(rs.getObject("price")).isNull();
            assertThat(rs.getObject("active")).isNull();
            assertThat(rs.getObject("created_at")).isNull();
        }
    }

    @Test
    void refusesTargetThatAlreadyHasData() throws Exception {
        execute(target, "INSERT INTO \"category\" VALUES (9, '已有')");

        assertThatThrownBy(() -> new TableCopier().copy(source, target))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("category");
        assertThat(count("product")).isZero();
    }

    @Test
    void failureLeavesTargetEmpty() throws Exception {
        // 新库的名称列更短，复制商品时出错，前面已复制的分类也要回滚
        execute(target, "ALTER TABLE \"product\" ALTER COLUMN \"name\" SET DATA TYPE VARCHAR(3)");

        assertThatThrownBy(() -> new TableCopier().copy(source, target)).isInstanceOf(SQLException.class);
        assertThat(count("category")).isZero();
        assertThat(count("product")).isZero();
    }

    private long count(String table) throws SQLException {
        try (Statement statement = target.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
