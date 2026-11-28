import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Storm 商城压测工具（单文件，只用 JDK，无需安装其他东西）。
 *
 * <p>模拟多位顾客同时浏览首页、商品列表、商品详情，试算运费并下单（线下收款订单），统计每类请求的吞吐量、
 * 响应时间分位数和错误率；结束后核对每个压测商品“扣掉的库存 = 成功下单的件数”，验证高并发下没有超卖或少扣。
 *
 * <pre>
 * java tools/LoadTest.java --base https://shop.example.com --users 50 --duration 120 \
 *     --admin-password-file secrets/ADMIN_PASSWORD [--insecure] [--report report.md]
 * </pre>
 *
 * 需要后台管理员密码：压测前用它创建几个库存充足的压测商品（名称以“压测商品”开头，结束后自动下架）。
 * 下单接口默认每个 IP 10 分钟限 20 单，压测环境要把 ORDER_PER_IP_LIMIT 调大，否则大部分下单会被限流（429）。
 * <b>不要对正式营业的商城压测</b>：会产生大量测试订单，请在预发布环境进行（见 docs/staging.md）。
 */
public class LoadTest {

    static final Map<String, Integer> WEIGHTS = new LinkedHashMap<>();
    static {
        WEIGHTS.put("首页", 30);
        WEIGHTS.put("商品列表接口", 25);
        WEIGHTS.put("商品详情页", 20);
        WEIGHTS.put("运费试算", 10);
        WEIGHTS.put("下单", 15);
    }

    static final Pattern ORDER_NO = Pattern.compile("\"orderNo\"\\s*:\\s*\"([^\"]+)\"");
    static final java.util.Set<String> ORDER_NOS = ConcurrentHashMap.newKeySet();

    record Options(String base, int users, int durationSeconds, boolean insecure, String adminUser,
                   String adminPassword, int products, double maxErrorRate, long maxP95Ms, Path report) {
    }

    static final class Stats {
        final List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger errors = new AtomicInteger();
        final Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        HttpClient admin = client(o, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        System.out.printf("压测目标 %s：%d 位并发顾客，持续 %d 秒%n", o.base, o.users, o.durationSeconds);

        List<Long> productIds = setupProducts(admin, o);
        Map<Long, Integer> initialStock = new LinkedHashMap<>();
        for (Long id : productIds) {
            initialStock.put(id, adminStock(admin, o, id));
        }
        System.out.println("压测商品：" + productIds);

        Map<String, Stats> stats = new LinkedHashMap<>();
        WEIGHTS.keySet().forEach(k -> stats.put(k, new Stats()));
        Map<Long, AtomicInteger> ordered = new ConcurrentHashMap<>();
        productIds.forEach(id -> ordered.put(id, new AtomicInteger()));
        AtomicLong rateLimited = new AtomicLong();

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(o.durationSeconds);
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < o.users; u++) {
                pool.submit(() -> {
                    HttpClient c = client(o, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                    while (System.nanoTime() < end) {
                        runOne(c, o, productIds, stats, ordered, rateLimited);
                    }
                    return null;
                });
            }
        }
        double elapsed = (System.nanoTime() - started) / 1e9;

        // 核对库存：每个压测商品扣掉的库存必须等于成功下单的件数
        List<String> stockProblems = new ArrayList<>();
        for (Long id : productIds) {
            int now = adminStock(admin, o, id);
            int deducted = initialStock.get(id) - now;
            int orders = ordered.get(id).get();
            if (deducted != orders) {
                stockProblems.add("商品 " + id + "：成功下单 " + orders + " 件，库存却减少了 " + deducted);
            }
        }
        for (Long id : productIds) {
            offShelf(admin, o, id);
        }

        String report = report(o, stats, elapsed, ordered, rateLimited.get(), stockProblems);
        System.out.println(report);
        if (o.report != null) {
            Files.writeString(o.report, report, StandardCharsets.UTF_8);
        }
        boolean ok = checkThresholds(o, stats, stockProblems);
        System.exit(ok ? 0 : 1);
    }

    static void runOne(HttpClient c, Options o, List<Long> productIds, Map<String, Stats> stats,
                       Map<Long, AtomicInteger> ordered, AtomicLong rateLimited) {
        String scenario = pick();
        long productId = productIds.get(ThreadLocalRandom.current().nextInt(productIds.size()));
        HttpRequest.Builder req;
        switch (scenario) {
            case "首页" -> req = get(o, "/");
            case "商品列表接口" -> req = get(o, "/api/products?page=0&size=20");
            case "商品详情页" -> req = get(o, "/products/" + productId);
            case "运费试算" -> req = postJson(o, "/api/shipping/quote",
                    "{\"currency\":\"CNY\",\"country\":\"CN\",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}");
            // 每笔订单带不同的备注：内容相同的重复提交会被商城当成双击，30 秒内直接返回上一笔订单
            default -> req = postJson(o, "/api/orders", "{\"currency\":\"CNY\",\"customerName\":\"压测顾客\","
                    + "\"phone\":\"13800000000\",\"address\":\"压测地址 1 号\",\"country\":\"CN\","
                    + "\"note\":\"压测 " + java.util.UUID.randomUUID() + "\","
                    + "\"email\":\"loadtest@example.invalid\",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}");
        }
        Stats s = stats.get(scenario);
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> res = c.send(req.build(), HttpResponse.BodyHandlers.ofString());
            long micros = (System.nanoTime() - t0) / 1000;
            s.latenciesMicros.add(micros);
            s.statuses.computeIfAbsent(res.statusCode(), k -> new AtomicInteger()).incrementAndGet();
            if (scenario.equals("下单") && res.statusCode() == 201) {
                // 按订单号去重计数：同一笔订单的重复响应不重复算
                Matcher m = ORDER_NO.matcher(res.body());
                if (m.find() && ORDER_NOS.add(m.group(1))) {
                    ordered.get(productId).incrementAndGet();
                }
            } else if (res.statusCode() == 429) {
                rateLimited.incrementAndGet();
                s.errors.incrementAndGet();
            } else if (res.statusCode() >= 400) {
                s.errors.incrementAndGet();
            }
        } catch (IOException | InterruptedException e) {
            s.latenciesMicros.add((System.nanoTime() - t0) / 1000);
            s.errors.incrementAndGet();
            s.statuses.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    static String pick() {
        int total = WEIGHTS.values().stream().mapToInt(Integer::intValue).sum();
        int r = ThreadLocalRandom.current().nextInt(total);
        for (var e : WEIGHTS.entrySet()) {
            r -= e.getValue();
            if (r < 0) {
                return e.getKey();
            }
        }
        return "首页";
    }

    // ---------- 后台：创建压测商品、查库存、下架 ----------

    static List<Long> setupProducts(HttpClient admin, Options o) throws Exception {
        String csrf = loginAdmin(admin, o);
        List<Long> ids = new ArrayList<>();
        String run = Long.toString(System.currentTimeMillis(), 36);
        for (int i = 1; i <= o.products; i++) {
            String body = "{\"name\":\"压测商品 " + run + "-" + i + "\",\"price\":10,\"stock\":1000000,\"status\":\"ON_SALE\"}";
            HttpResponse<String> res = admin.send(postJson(o, "/api/admin/products", body)
                    .header("X-XSRF-TOKEN", csrf).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 201 && res.statusCode() != 200) {
                throw new IllegalStateException("创建压测商品失败：" + res.statusCode() + " " + res.body());
            }
            ids.add(Long.parseLong(field(res.body(), "id")));
        }
        return ids;
    }

    static String loginAdmin(HttpClient admin, Options o) throws Exception {
        admin.send(get(o, "/api/admin/session").build(), HttpResponse.BodyHandlers.discarding());
        String csrf = xsrf(admin);
        String body = "{\"username\":\"" + o.adminUser + "\",\"password\":\"" + o.adminPassword.replace("\"", "\\\"") + "\"}";
        HttpResponse<String> res = admin.send(postJson(o, "/api/admin/session", body).header("X-XSRF-TOKEN", csrf).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 || !res.body().contains("\"authenticated\":true")) {
            throw new IllegalStateException("后台登录失败：" + res.statusCode() + " " + res.body());
        }
        return xsrf(admin);
    }

    static String xsrf(HttpClient c) {
        CookieManager cm = (CookieManager) c.cookieHandler().orElseThrow();
        return cm.getCookieStore().getCookies().stream().filter(k -> k.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElseThrow(() -> new IllegalStateException("没有拿到 CSRF 令牌"));
    }

    static int adminStock(HttpClient admin, Options o, long id) throws Exception {
        HttpResponse<String> res = admin.send(get(o, "/api/admin/products/" + id).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("查询库存失败：" + res.statusCode());
        }
        return Integer.parseInt(field(res.body(), "stock"));
    }

    static void offShelf(HttpClient admin, Options o, long id) {
        try {
            HttpResponse<String> cur = admin.send(get(o, "/api/admin/products/" + id).build(), HttpResponse.BodyHandlers.ofString());
            String body = "{\"name\":\"" + jsonString(cur.body(), "name") + "\",\"price\":10,\"stock\":" + field(cur.body(), "stock")
                    + ",\"status\":\"OFF_SHELF\",\"version\":" + field(cur.body(), "version") + "}";
            admin.send(HttpRequest.newBuilder(URI.create(o.base + "/api/admin/products/" + id)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf(admin))
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("下架压测商品 " + id + " 失败，请到后台手动下架：" + e.getMessage());
        }
    }

    // ---------- 报告 ----------

    static String report(Options o, Map<String, Stats> stats, double elapsed, Map<Long, AtomicInteger> ordered,
                         long rateLimited, List<String> stockProblems) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 压测结果\n\n");
        sb.append(String.format(Locale.ROOT, "- 目标：%s，%d 位并发顾客，持续 %.0f 秒%n", o.base, o.users, elapsed));
        long total = 0;
        long errors = 0;
        List<Long> all = new ArrayList<>();
        sb.append("\n| 请求 | 次数 | 每秒 | p50 | p95 | p99 | 最慢 | 错误 | 状态码 |\n|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (var e : stats.entrySet()) {
            Stats s = e.getValue();
            List<Long> l;
            synchronized (s.latenciesMicros) {
                l = new ArrayList<>(s.latenciesMicros);
            }
            Collections.sort(l);
            total += l.size();
            errors += s.errors.get();
            all.addAll(l);
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %s | %s | %s | %s | %d | %s |%n", e.getKey(), l.size(),
                    l.size() / elapsed, ms(l, 50), ms(l, 95), ms(l, 99), l.isEmpty() ? "-" : fmt(l.get(l.size() - 1)),
                    s.errors.get(), new java.util.TreeMap<>(s.statuses)));
        }
        Collections.sort(all);
        sb.append(String.format(Locale.ROOT, "| **合计** | %d | **%.1f** | %s | **%s** | %s | %s | %d | |%n", total, total / elapsed,
                ms(all, 50), ms(all, 95), ms(all, 99), all.isEmpty() ? "-" : fmt(all.get(all.size() - 1)), errors));
        int orders = ordered.values().stream().mapToInt(AtomicInteger::get).sum();
        sb.append(String.format(Locale.ROOT, "%n- 成功下单 %d 笔（约 %.1f 单/秒，折合 %.0f 单/小时）%n", orders, orders / elapsed,
                orders / elapsed * 3600));
        sb.append(String.format(Locale.ROOT, "- 错误率 %.2f%%%s%n", total == 0 ? 0 : errors * 100.0 / total,
                rateLimited > 0 ? "（其中被限流 429：" + rateLimited + " 次，压测环境请调大 ORDER_PER_IP_LIMIT）" : ""));
        sb.append(stockProblems.isEmpty() ? "- 库存核对：通过（扣掉的库存与成功下单件数完全一致，无超卖、无少扣）\n"
                : "- 库存核对：**失败**\n  - " + String.join("\n  - ", stockProblems) + "\n");
        return sb.toString();
    }

    static boolean checkThresholds(Options o, Map<String, Stats> stats, List<String> stockProblems) {
        long total = 0;
        long errors = 0;
        List<Long> all = new ArrayList<>();
        for (Stats s : stats.values()) {
            synchronized (s.latenciesMicros) {
                all.addAll(s.latenciesMicros);
            }
            errors += s.errors.get();
        }
        total = all.size();
        Collections.sort(all);
        double errorRate = total == 0 ? 1 : (double) errors / total;
        long p95 = percentile(all, 95) / 1000;
        boolean ok = true;
        if (errorRate > o.maxErrorRate) {
            System.err.printf(Locale.ROOT, "未通过：错误率 %.2f%% 超过上限 %.2f%%%n", errorRate * 100, o.maxErrorRate * 100);
            ok = false;
        }
        if (p95 > o.maxP95Ms) {
            System.err.printf("未通过：95 分位响应时间 %d 毫秒超过上限 %d 毫秒%n", p95, o.maxP95Ms);
            ok = false;
        }
        if (!stockProblems.isEmpty()) {
            System.err.println("未通过：库存与订单对不上");
            ok = false;
        }
        if (ok) {
            System.out.println("压测通过");
        }
        return ok;
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(i, sorted.size() - 1)));
    }

    static String ms(List<Long> sorted, int p) {
        return sorted.isEmpty() ? "-" : fmt(percentile(sorted, p));
    }

    static String fmt(long micros) {
        return micros >= 1000_000 ? String.format(Locale.ROOT, "%.2fs", micros / 1e6)
                : String.format(Locale.ROOT, "%dms", Math.round(micros / 1000.0));
    }

    // ---------- HTTP 工具 ----------

    static HttpClient client(Options o, CookieManager cookies) {
        HttpClient.Builder b = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
        if (o.insecure) {
            b.sslContext(trustAll());
        }
        return b.build();
    }

    static HttpRequest.Builder get(Options o, String path) {
        return HttpRequest.newBuilder(URI.create(o.base + path)).timeout(Duration.ofSeconds(30)).GET();
    }

    static HttpRequest.Builder postJson(Options o, String path, String json) {
        return HttpRequest.newBuilder(URI.create(o.base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }

    /** 只用于 --insecure：自签名证书的预发布 / CI 环境。 */
    static SSLContext trustAll() {
        try {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("响应里没有数字字段 " + name + "：" + json);
        }
        return m.group(1);
    }

    static String jsonString(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : "压测商品";
    }

    static Options parse(String[] args) throws IOException {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String key = args[i].substring(2);
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            a.put(key, flag ? "true" : args[++i]);
        }
        if (!a.containsKey("base")) {
            System.err.println("用法：java tools/LoadTest.java --base https://你的预发布地址 --admin-password-file 文件 "
                    + "[--users 50] [--duration 60] [--products 5] [--insecure] [--max-error-rate 0.01] [--max-p95-ms 1500] [--report 文件]");
            System.exit(2);
        }
        String password = a.containsKey("admin-password-file")
                ? Files.readString(Path.of(a.get("admin-password-file"))).trim()
                : System.getenv().getOrDefault("ADMIN_PASSWORD", "");
        if (password.isEmpty()) {
            System.err.println("需要后台管理员密码：--admin-password-file 文件，或环境变量 ADMIN_PASSWORD");
            System.exit(2);
        }
        String base = a.get("base").replaceAll("/+$", "");
        return new Options(base, Integer.parseInt(a.getOrDefault("users", "50")),
                Integer.parseInt(a.getOrDefault("duration", "60")), Boolean.parseBoolean(a.getOrDefault("insecure", "false")),
                a.getOrDefault("admin-user", "admin"), password, Integer.parseInt(a.getOrDefault("products", "5")),
                Double.parseDouble(a.getOrDefault("max-error-rate", "0.01")), Long.parseLong(a.getOrDefault("max-p95-ms", "1500")),
                a.containsKey("report") ? Path.of(a.get("report")) : null);
    }
}
