package com.javastorm.shop.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一个数据库里所有表的数据原样复制到另一个数据库。目标库的表结构须已建好（由程序启动时创建）且为空。
 *
 * <p>表名、列名不区分大小写对应（H2 是大写，MySQL 是小写）；主键原样复制，MySQL 自增值会自动接在最大值之后。
 * 整个复制在一个事务里完成，出错时目标库回滚为空。
 */
public class TableCopier {

    private static final int BATCH_SIZE = 500;

    /** 复制结果：每张表的行数，以及源库里目标库已不再使用的表和列（旧版本遗留，不复制）。 */
    public record Report(Map<String, Long> rows, List<String> skippedTables, List<String> skippedColumns) {
    }

    /** 列名和 JDBC 类型。 */
    private record Column(String name, int type) {
    }

    public Report copy(Connection source, Connection target) throws SQLException, MigrationException {
        Map<String, String> sourceTables = tables(source);
        Map<String, String> targetTables = tables(target);
        List<String> notEmpty = new ArrayList<>();
        for (String table : targetTables.values()) {
            if (count(target, table) > 0) {
                notEmpty.add(table);
            }
        }
        if (!notEmpty.isEmpty()) {
            throw new MigrationException("目标数据库里已经有数据（" + String.join("、", notEmpty)
                    + "），为避免覆盖，只能迁移到空数据库。请删掉这个数据库重新创建后再试。");
        }

        Map<String, Long> rows = new LinkedHashMap<>();
        List<String> skippedTables = new ArrayList<>();
        List<String> skippedColumns = new ArrayList<>();
        boolean autoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        setForeignKeyChecks(target, false);
        try {
            for (Map.Entry<String, String> entry : sourceTables.entrySet()) {
                String targetTable = targetTables.get(entry.getKey());
                if (targetTable == null) {
                    skippedTables.add(entry.getValue());
                    continue;
                }
                rows.put(targetTable, copyTable(source, entry.getValue(), target, targetTable, skippedColumns));
            }
            for (Map.Entry<String, Long> entry : rows.entrySet()) {
                long copied = count(target, entry.getKey());
                if (copied != entry.getValue()) {
                    throw new MigrationException("表 " + entry.getKey() + " 应有 " + entry.getValue() + " 行，实际 " + copied + " 行");
                }
            }
            target.commit();
        } catch (SQLException | MigrationException | RuntimeException e) {
            target.rollback();
            throw e;
        } finally {
            setForeignKeyChecks(target, true);
            target.setAutoCommit(autoCommit);
        }
        return new Report(rows, skippedTables, skippedColumns);
    }

    private long copyTable(Connection source, String sourceTable, Connection target, String targetTable,
                           List<String> skippedColumns) throws SQLException {
        Map<String, Column> sourceColumns = columns(source, sourceTable);
        Map<String, Column> targetColumns = columns(target, targetTable);
        List<Column> from = new ArrayList<>();
        List<Column> to = new ArrayList<>();
        for (Map.Entry<String, Column> entry : sourceColumns.entrySet()) {
            Column column = targetColumns.get(entry.getKey());
            if (column == null) {
                skippedColumns.add(sourceTable + "." + entry.getValue().name());
            } else {
                from.add(entry.getValue());
                to.add(column);
            }
        }
        if (from.isEmpty()) {
            return 0;
        }
        String sourceQuote = source.getMetaData().getIdentifierQuoteString().strip();
        String targetQuote = target.getMetaData().getIdentifierQuoteString().strip();
        String select = "SELECT " + joinNames(from, sourceQuote) + " FROM " + quote(sourceTable, sourceQuote);
        String insert = "INSERT INTO " + quote(targetTable, targetQuote) + " (" + joinNames(to, targetQuote)
                + ") VALUES (" + "?, ".repeat(to.size() - 1) + "?)";
        long copied = 0;
        try (Statement read = source.createStatement();
             ResultSet rs = read.executeQuery(select);
             PreparedStatement write = target.prepareStatement(insert)) {
            int pending = 0;
            while (rs.next()) {
                for (int i = 0; i < from.size(); i++) {
                    write.setObject(i + 1, value(rs, i + 1, from.get(i).type()));
                }
                write.addBatch();
                copied++;
                if (++pending == BATCH_SIZE) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
        return copied;
    }

    /** 按类型读出值；日期时间用 java.time 读，避免经过时区换算。 */
    private static Object value(ResultSet rs, int index, int type) throws SQLException {
        Object value = switch (type) {
            case Types.TIMESTAMP -> rs.getObject(index, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(index, OffsetDateTime.class);
            case Types.DATE -> rs.getObject(index, LocalDate.class);
            case Types.TIME -> rs.getObject(index, LocalTime.class);
            // H2 的 ENUM 等类型按文字复制
            case Types.OTHER, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB, Types.NCHAR,
                 Types.NVARCHAR, Types.LONGNVARCHAR, Types.NCLOB -> rs.getString(index);
            default -> rs.getObject(index);
        };
        return rs.wasNull() ? null : value;
    }

    /** 当前库里的表，键为小写表名。 */
    private static Map<String, String> tables(Connection connection) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), schema(connection), "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                tables.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return tables;
    }

    /** 表的列，键为小写列名。 */
    private static Map<String, Column> columns(Connection connection, String table) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), schema(connection), table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                columns.put(name.toLowerCase(Locale.ROOT), new Column(name, rs.getInt("DATA_TYPE")));
            }
        }
        return columns;
    }

    /** MySQL 用 catalog 区分数据库，schema 为空；H2 的表在 PUBLIC 下。 */
    private static String schema(Connection connection) throws SQLException {
        return isMySql(connection) ? null : connection.getSchema();
    }

    private static long count(Connection connection, String table) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString().strip();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + quote(table, quote))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void setForeignKeyChecks(Connection connection, boolean enabled) throws SQLException {
        String sql = isMySql(connection)
                ? "SET FOREIGN_KEY_CHECKS = " + (enabled ? 1 : 0)
                : "SET REFERENTIAL_INTEGRITY " + (enabled ? "TRUE" : "FALSE");
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static boolean isMySql(Connection connection) throws SQLException {
        return "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
    }

    private static String joinNames(List<Column> columns, String quote) {
        List<String> names = new ArrayList<>();
        for (Column column : columns) {
            names.add(quote(column.name(), quote));
        }
        return String.join(", ", names);
    }

    private static String quote(String name, String quote) {
        return quote + name.replace(quote, quote + quote) + quote;
    }
}
