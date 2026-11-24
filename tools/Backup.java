import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
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
 * 读取 config.env 里的 DB_URL、DB_USERNAME、DB_PASSWORD、UPLOAD_DIR、BACKUP_DIR、BACKUP_KEEP，
 * 环境变量优先。
 */
public class Backup {

    private static final String DEFAULT_DB_URL = "jdbc:h2:file:./data/shop;AUTO_SERVER=TRUE";
    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";
    private static final String NAME_PREFIX = "storm-backup-";
    /** H2 数据库正被另一个程序独占打开。 */
    private static final int DATABASE_ALREADY_OPEN = 90020;

    private static final String RESTORE_GUIDE = """
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
        if (!url.startsWith(H2_FILE_PREFIX)) {
            throw new BackupException("本脚本只能备份商城自带的本地数据库（H2），当前 DB_URL=" + url);
        }
        Path database = databaseFile(root, url);
        if (!Files.exists(database)) {
            throw new BackupException("没有找到数据库文件 " + database + "，商城至少要启动过一次才会生成。");
        }
        Path uploads = root.resolve(setting(config, "UPLOAD_DIR", "./uploads")).normalize();
        Path backupDir = root.resolve(setting(config, "BACKUP_DIR", "./backups")).normalize();
        int keep = parseKeep(setting(config, "BACKUP_KEEP", "30"));

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.createDirectories(backupDir);
        Path work = Files.createTempDirectory(backupDir, ".working-");
        try {
            System.out.println("正在备份数据库……");
            Path dump = work.resolve("database.zip");
            backupDatabase(url, setting(config, "DB_USERNAME", "sa"), setting(config, "DB_PASSWORD", ""), dump);

            System.out.println("正在打包图片……");
            Path partial = work.resolve("backup.zip");
            int images = writeArchive(partial, dump, uploads, stamp);
            verify(partial);

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
    private static void backupDatabase(String url, String user, String password, Path dump)
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

    private static int writeArchive(Path archive, Path dump, Path uploads, String stamp) throws IOException {
        int images = 0;
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            addFile(zip, "database.zip", dump);
            zip.putNextEntry(new ZipEntry("恢复说明.txt"));
            String guide = RESTORE_GUIDE.formatted(stamp).replace("\n", "\r\n");
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
    private static void verify(Path archive) throws IOException, BackupException {
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            if (zip.getEntry("database.zip") == null) {
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
