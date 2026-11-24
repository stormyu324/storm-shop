import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Storm 商城一键备份：把数据库和上传的图片打包成一个 zip，商城运行中也可以备份。
 *
 * <p>由 backup.bat / backup.command / backup.sh 调用，也可以在项目目录运行 {@code java tools/Backup.java}。
 * 读取 config.env 里的 DB_URL、DB_USERNAME、DB_PASSWORD、UPLOAD_DIR、BACKUP_DIR、BACKUP_KEEP、MYSQLDUMP，
 * 环境变量优先。自带的 H2 数据库用它的 BACKUP 命令导出；MySQL 用 mysqldump 导出。
 */
public class Backup {

    private static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/shop;AUTO_SERVER=TRUE";
    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";
    private static final String MYSQL_PREFIX = "jdbc:mysql://";
    private static final String NAME_PREFIX = "storm-backup-";
    /** H2 数据库正被另一个程序独占打开。 */
    private static final int DATABASE_ALREADY_OPEN = 90020;

    private static final String H2_GUIDE = """
            Storm 商城备份 %s

            包含：
              database.zip   数据库（商品、订单、分类、运费、优惠券、会员、员工账号、操作记录）
              uploads/       后台上传的商品图片
            不包含 config.env（里面有密码和密钥），请另外妥善保存。

            恢复步骤：
              1. 关掉商城窗口，确认商城已停止。
              2. 把项目文件夹里现有的 data 文件夹改名（例如 data-旧），留作后悔药。
              3. 新建 data 文件夹，把 database.zip 里的 shop.mv.db 解压进去。
              4. 把备份里的 uploads 文件夹复制到项目文件夹，覆盖原来的 uploads。
              5. 重新启动商城，检查商品和订单是否正常。
            """;

    private static final String MYSQL_GUIDE = """
            Storm 商城备份 %s（MySQL）

            包含：
              database.sql   MySQL 数据库导出（商品、订单、分类、运费、优惠券、会员、员工账号、操作记录）
              uploads/       后台上传的商品图片
            不包含 config.env（里面有密码和密钥），请另外妥善保存。

            恢复步骤：
              1. 关掉商城窗口，确认商城已停止。
              2. 按 README“换成 MySQL”里的语句新建一个空数据库（例如 shop_restore），
                 或者确认不要现有数据后，把原数据库删掉重建。
              3. 在命令行导入（会提示输入 MySQL 密码）：
                   mysql -u shop -p shop_restore < database.sql
              4. 如果换了数据库名，修改 config.env 里的 DB_URL。
              5. 把备份里的 uploads 文件夹复制到项目文件夹，覆盖原来的 uploads。
              6. 重新启动商城，检查商品和订单是否正常。
            """;

    public static void main(String[] args) {
        try {
            run(Path.of("").toAbsolutePath());
        } catch (BackupException e) {
            System.out.println();
            System.out.println("备份失败：" + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.out.println();
            System.out.println("备份失败，读写文件出错：" + e);
            System.exit(1);
        }
    }

    private static void run(Path root) throws IOException, BackupException {
        Map<String, String> config = readConfig(root.resolve("config.env"));
        String url = setting(config, "DB_URL", DEFAULT_DB_URL);
        boolean mysql = url.startsWith(MYSQL_PREFIX);
        if (!mysql && !url.startsWith(H2_FILE_PREFIX)) {
            throw new BackupException("只能备份商城自带的数据库（H2）或 MySQL，当前 DB_URL=" + url);
        }
        if (!mysql) {
            Path database = databaseFile(root, url);
            if (!Files.exists(database)) {
                throw new BackupException("没有找到数据库文件 " + database + "，商城至少要启动过一次才会生成。");
            }
        }
        String user = setting(config, "DB_USERNAME", "sa");
        String password = setting(config, "DB_PASSWORD", "");
        Path uploads = root.resolve(setting(config, "UPLOAD_DIR", "./uploads")).normalize();
        Path backupDir = root.resolve(setting(config, "BACKUP_DIR", "./backups")).normalize();
        int keep = parseKeep(setting(config, "BACKUP_KEEP", "30"));

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.createDirectories(backupDir);
        Path work = Files.createTempDirectory(backupDir, ".working-");
        try {
            System.out.println("正在备份数据库……");
            Path dump;
            String guide;
            if (mysql) {
                dump = work.resolve("database.sql");
                backupMySql(url, user, password, setting(config, "MYSQLDUMP", ""), work, dump);
                guide = MYSQL_GUIDE;
            } else {
                dump = work.resolve("database.zip");
                backupH2(url, user, password, dump);
                guide = H2_GUIDE;
            }

            System.out.println("正在打包图片……");
            Path partial = work.resolve("backup.zip");
            int images = writeArchive(partial, dump, uploads, guide.formatted(stamp));
            verify(partial, dump.getFileName().toString());

            Path target = backupDir.resolve(NAME_PREFIX + stamp + ".zip");
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            List<Path> removed = prune(backupDir, keep);

            System.out.println();
            System.out.println("备份完成：" + target);
            System.out.printf("大小 %s，数据库 %s，图片 %d 个%n", size(Files.size(target)), size(Files.size(dump)), images);
            if (!removed.isEmpty()) {
                System.out.printf("只保留最近 %d 份，已删除 %d 份旧备份%n", keep, removed.size());
            }
            System.out.println("建议再复制一份到 U 盘或网盘。config.env 里有密码和密钥，没有放进备份，请另外保存。");
        } finally {
            deleteRecursively(work);
        }
    }

    /** 用 H2 的 BACKUP 命令导出一致的数据库快照；配置了 AUTO_SERVER 时，商城运行中也能连上。 */
    private static void backupH2(String url, String user, String password, Path dump)
            throws BackupException, IOException {
        Path jar = findH2Jar();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()})) {
            Driver driver = (Driver) Class.forName("org.h2.Driver", true, loader)
                    .getDeclaredConstructor().newInstance();
            Properties credentials = new Properties();
            credentials.setProperty("user", user);
            credentials.setProperty("password", password);
            // IFEXISTS：数据库不存在时报错，而不是新建一个空库
            try (Connection connection = driver.connect(url + ";IFEXISTS=TRUE", credentials);
                 Statement statement = connection.createStatement()) {
                statement.execute("BACKUP TO '" + dump.toString().replace("'", "''") + "'");
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == DATABASE_ALREADY_OPEN) {
                throw new BackupException("数据库正被另一个程序占用（DB_URL 里没有 AUTO_SERVER=TRUE 时，运行中无法备份），"
                        + "请关掉商城窗口后再备份。");
            }
            throw new BackupException("数据库备份出错：" + e.getMessage());
        } catch (ReflectiveOperationException e) {
            throw new BackupException("无法加载 H2 数据库驱动 " + jar + "：" + e);
        }
        if (!Files.exists(dump)) {
            throw new BackupException("数据库没有导出到 " + dump + "，请关掉商城窗口后再试一次。");
        }
    }

    /**
     * 用 mysqldump 导出 MySQL。--single-transaction 在一个事务里读出一致的快照，商城运行中也能备份；
     * 密码写进临时选项文件传给 mysqldump，不出现在命令行里。
     */
    private static void backupMySql(String url, String user, String password, String mysqldump, Path work, Path dump)
            throws BackupException, IOException {
        String rest = url.substring(MYSQL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new BackupException("DB_URL 格式不对，应为 jdbc:mysql://主机:端口/数据库名，当前是 " + url);
        }
        String address = rest.substring(0, slash);
        String database = rest.substring(slash + 1).split("[?;]", 2)[0];
        if (address.contains(",") || database.isBlank()) {
            throw new BackupException("DB_URL 格式不对，应为 jdbc:mysql://主机:端口/数据库名，当前是 " + url);
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : address;
        String port = colon > 0 ? address.substring(colon + 1) : "3306";

        String executable = findMysqldump(mysqldump);
        // 放在系统临时目录（不在备份文件夹里，免得被网盘同步），只有当前用户可读
        Path options = Files.createTempFile("storm-mysql-", ".cnf");
        Path log = work.resolve("mysqldump.log");
        int exit;
        try {
            Files.writeString(options, "[client]\nuser=\"" + escapeOption(user) + "\"\npassword=\"" + escapeOption(password)
                    + "\"\nhost=\"" + escapeOption(host) + "\"\nport=" + port + "\n", StandardCharsets.UTF_8);
            List<String> command = List.of(executable, "--defaults-extra-file=" + options,
                    "--single-transaction", "--no-tablespaces", "--default-character-set=utf8mb4",
                    "--result-file=" + dump, database);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("备份被中断");
        } finally {
            Files.deleteIfExists(options);
        }
        String output = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8).strip() : "";
        if (exit != 0 || !Files.exists(dump) || !tail(dump).contains("-- Dump completed")) {
            throw new BackupException("mysqldump 导出失败" + (output.isEmpty() ? "" : "：" + output)
                    + "\n请检查 MySQL 是否在运行，以及 config.env 里的 DB_URL、DB_USERNAME、DB_PASSWORD。");
        }
    }

    /** 文件最后 1 KB：mysqldump 成功时最后一行是 "-- Dump completed"。 */
    private static String tail(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, 1024));
            channel.position(size - buffer.capacity());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读满缓冲区
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    /** 依次在 MYSQLDUMP 设置、PATH 和 MySQL 的常见安装位置里找 mysqldump。 */
    private static String findMysqldump(String configured) throws BackupException, IOException {
        if (!configured.isBlank()) {
            if (Files.isRegularFile(Path.of(configured))) {
                return configured;
            }
            throw new BackupException("config.env 里的 MYSQLDUMP 指向的文件不存在：" + configured);
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "mysqldump.exe" : "mysqldump";
        List<Path> dirs = new ArrayList<>();
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isBlank()) {
                dirs.add(Path.of(dir.replace("\"", "")));
            }
        }
        if (windows) {
            Path mysqlHome = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "MySQL");
            if (Files.isDirectory(mysqlHome)) {
                try (Stream<Path> servers = Files.list(mysqlHome)) {
                    servers.sorted(Comparator.reverseOrder()).forEach(server -> dirs.add(server.resolve("bin")));
                }
            }
        } else {
            dirs.addAll(List.of(Path.of("/usr/local/mysql/bin"), Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"),
                    Path.of("/opt/homebrew/opt/mysql-client/bin"), Path.of("/usr/local/opt/mysql-client/bin")));
        }
        for (Path dir : dirs) {
            Path candidate = dir.resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        throw new BackupException("没有找到 mysqldump（随 MySQL 一起安装）。请在 config.env 里用 MYSQLDUMP 指定它的完整路径，"
                + "例如 MYSQLDUMP=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe");
    }

    /** MySQL 选项文件里双引号内的值：转义反斜杠和双引号。 */
    private static String escapeOption(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 在 Maven 本地仓库里找 H2 驱动；商城第一次启动时会自动下载。 */
    private static Path findH2Jar() throws BackupException, IOException {
        Path base = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "h2database", "h2");
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> versions = Files.list(base)) {
                for (Path version : versions.filter(Files::isDirectory).toList()) {
                    Path jar = version.resolve("h2-" + version.getFileName() + ".jar");
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    }
                }
            }
        }
        return jars.stream()
                .max(Comparator.comparing(jar -> jar.getParent().getFileName().toString(), Backup::compareVersions))
                .orElseThrow(() -> new BackupException("没有找到 H2 数据库驱动，请先用启动脚本启动一次商城（会自动下载）。"));
    }

    private static int writeArchive(Path archive, Path dump, Path uploads, String guideText) throws IOException {
        int images = 0;
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            addFile(zip, dump.getFileName().toString(), dump);
            zip.putNextEntry(new ZipEntry("恢复说明.txt"));
            String guide = guideText.replace("\n", "\r\n");
            zip.write(guide.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (Files.isDirectory(uploads)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(uploads)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path image : files) {
                    String name = uploads.relativize(image).toString().replace('\\', '/');
                    addFile(zip, "uploads/" + name, image);
                    images++;
                }
            }
        }
        return images;
    }

    private static void addFile(ZipOutputStream zip, String name, Path source) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(Files.getLastModifiedTime(source));
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
    }

    /** 把每个文件完整读一遍，校验压缩包没有损坏。 */
    private static void verify(Path archive, String databaseEntry) throws IOException, BackupException {
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry(databaseEntry) == null) {
                throw new BackupException("压缩包里缺少数据库文件");
            }
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    while (in.read(buffer) >= 0) {
                        // 读到结尾时会校验 CRC
                    }
                }
            }
        }
    }

    /** 按文件名里的时间只保留最近 keep 份，keep 为 0 时全部保留。 */
    private static List<Path> prune(Path backupDir, int keep) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, NAME_PREFIX + "*.zip")) {
            stream.forEach(backups::add);
        }
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        List<Path> removed = new ArrayList<>();
        if (keep > 0) {
            for (Path old : backups.subList(Math.min(keep, backups.size()), backups.size())) {
                Files.deleteIfExists(old);
                removed.add(old);
            }
        }
        return removed;
    }

    private static Path databaseFile(Path root, String url) {
        String name = url.substring(H2_FILE_PREFIX.length()).split(";", 2)[0];
        if (name.startsWith("~")) {
            name = System.getProperty("user.home") + name.substring(1);
        }
        return root.resolve(name + ".mv.db").normalize();
    }

    /** 读 config.env：KEY=VALUE，# 开头为注释，值两边的引号会去掉。 */
    private static Map<String, String> readConfig(Path file) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(file)) {
            return values;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                continue;
            }
            String value = trimmed.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(trimmed.substring(0, eq).strip(), value);
        }
        return values;
    }

    private static String setting(Map<String, String> config, String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = config.get(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parseKeep(String value) throws BackupException {
        try {
            int keep = Integer.parseInt(value.strip());
            if (keep < 0) {
                throw new NumberFormatException();
            }
            return keep;
        } catch (NumberFormatException e) {
            throw new BackupException("BACKUP_KEEP 须为 0 或正整数，当前是 " + value);
        }
    }

    private static int compareVersions(String a, String b) {
        String[] left = a.split("\\D+");
        String[] right = b.split("\\D+");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length && !left[i].isEmpty() ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length && !right[i].isEmpty() ? Integer.parseInt(right[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static final class BackupException extends Exception {
        BackupException(String message) {
            super(message);
        }
    }
}
