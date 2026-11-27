package com.javastorm.shop.service;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderItem;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.repository.OrderRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 批量打单与物流发货服务：支持导出待发货订单 CSV（供仓库/ERP/打单软件使用），
 * 以及上传物流单号 CSV 批量回填承运商、运单号并自动触发顾客发货通知邮件。
 */
@Service
public class OrderFulfillmentService {

    private static final int MAX_EXPORT_ROWS = 5_000;
    private static final int MAX_IMPORT_ROWS = 5_000;
    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderRepository orders;
    private final OrderCareService care;

    public OrderFulfillmentService(OrderRepository orders, OrderCareService care) {
        this.orders = orders;
        this.care = care;
    }

    public record RowError(int row, String orderNo, String message) {
    }

    public record BulkShipResult(int total, int succeeded, int failed, List<RowError> errors) {
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(OrderStatus status, boolean toFulfil, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_EXPORT_ROWS);
        var pageable = PageRequest.of(0, capped, Sort.by("id").ascending());
        List<CustomerOrder> list = toFulfil
                ? orders.findByStatusIn(OrderStatus.toFulfil(), pageable).getContent()
                : status != null
                ? orders.findByStatus(status, pageable).getContent()
                : orders.findAll(pageable).getContent();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(UTF8_BOM);
            try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                 CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                         .setHeader("订单号", "状态", "下单时间", "币种", "总额", "收件人", "电话", "邮箱",
                                 "国家", "收货地址", "备注", "商品明细", "承运商", "运单号", "物流链接")
                         .build())) {
                for (CustomerOrder o : list) {
                    String itemsSummary = o.getItems().stream()
                            .map(this::formatItem)
                            .collect(Collectors.joining(" | "));
                    csv.printRecord(
                            excelText(o.getOrderNo()),
                            o.getStatus().name(),
                            o.getCreatedAt() == null ? "" : o.getCreatedAt().format(TIME_FORMAT),
                            o.getCurrency() == null ? "CNY" : o.getCurrency().name(),
                            o.getTotal() == null ? "0.00" : o.getTotal().toPlainString(),
                            safe(o.getCustomerName()),
                            excelText(o.getPhone()),
                            safe(o.getEmail()),
                            safe(o.getCountry()),
                            safe(o.getAddress()),
                            safe(o.getNote()),
                            safe(itemsSummary),
                            safe(o.getCarrier()),
                            excelText(o.getTrackingNumber()),
                            safe(o.getTrackingUrl())
                    );
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("导出订单 CSV 失败：" + e.getMessage());
        }
    }

    public byte[] shipmentTemplateCsv() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(UTF8_BOM);
            try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                 CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                         .setHeader("订单号", "承运商", "运单号", "物流链接")
                         .build())) {
                csv.printRecord(excelText("202611251200000001"), "UPS", excelText("1Z999AA10123456784"),
                        "https://www.ups.com/track");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("生成模板失败：" + e.getMessage());
        }
    }

    /**
     * 逐行导入物流单号并标记发货；每行独立事务，单行失败不影响其余订单。
     * 同时兼容中英文表头，也支持直接上传导出后的待发货 CSV。
     */
    public BulkShipResult importShipmentsCsv(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("请上传非空的 CSV 文件");
        }
        String text = decodeCsv(bytes);
        List<RowError> errors = new ArrayList<>();
        int total = 0;
        int succeeded = 0;

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(text))) {

            Map<String, String> headers = normalizeHeaders(parser.getHeaderNames());
            String orderNoCol = findColumn(headers, "订单号", "orderno", "order_no", "order number");
            String carrierCol = findColumn(headers, "承运商", "物流公司", "carrier");
            String trackingCol = findColumn(headers, "运单号", "物流单号", "trackingnumber", "tracking_number", "tracking");
            String urlCol = findColumn(headers, "物流链接", "查询链接", "trackingurl", "tracking_url");

            if (orderNoCol == null || carrierCol == null || trackingCol == null) {
                throw new BusinessException("CSV 缺少必需列：请至少包含「订单号」「承运商」「运单号」（支持英文 orderNo/carrier/trackingNumber）");
            }

            int rowNum = 1;
            for (CSVRecord record : parser) {
                rowNum++;
                if (total >= MAX_IMPORT_ROWS) {
                    throw new BusinessException("单次最多导入 " + MAX_IMPORT_ROWS + " 条物流记录，请分批上传");
                }
                String orderNo = value(record, orderNoCol);
                String carrier = value(record, carrierCol);
                String tracking = value(record, trackingCol);
                String trackingUrl = urlCol == null ? null : value(record, urlCol);

                if (orderNo.isEmpty() && carrier.isEmpty() && tracking.isEmpty()) {
                    continue;
                }
                total++;

                if (orderNo.isEmpty()) {
                    errors.add(new RowError(rowNum, "", "订单号不能为空"));
                    continue;
                }
                if (EXCEL_MANGLED_NUMBER.matcher(orderNo).matches()) {
                    errors.add(new RowError(rowNum, orderNo,
                            "订单号被 Excel 改成了科学计数法，已无法还原；请重新导出，或把订单号列设为“文本”后重新填写"));
                    continue;
                }
                if (carrier.isEmpty() || tracking.isEmpty()) {
                    errors.add(new RowError(rowNum, orderNo, "承运商和运单号不能为空"));
                    continue;
                }
                if (carrier.length() > 100 || tracking.length() > 100) {
                    errors.add(new RowError(rowNum, orderNo, "承运商或运单号长度不能超过 100"));
                    continue;
                }
                if (trackingUrl != null && trackingUrl.length() > 254) {
                    errors.add(new RowError(rowNum, orderNo, "物流链接长度不能超过 254"));
                    continue;
                }

                try {
                    CustomerOrder order = orders.findByOrderNo(orderNo)
                            .orElseThrow(() -> new BusinessException("订单不存在"));
                    care.ship(order.getId(), carrier, tracking, trackingUrl == null || trackingUrl.isEmpty() ? null : trackingUrl);
                    succeeded++;
                } catch (BusinessException e) {
                    errors.add(new RowError(rowNum, orderNo, e.getMessage()));
                } catch (RuntimeException e) {
                    errors.add(new RowError(rowNum, orderNo, "处理失败：" + e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException("解析 CSV 失败：" + e.getMessage());
        }

        return new BulkShipResult(total, succeeded, total - succeeded, errors);
    }

    private String formatItem(OrderItem item) {
        String sku = item.getSku() == null || item.getSku().isBlank() ? "" : "[" + item.getSku() + "] ";
        return sku + item.getProductName() + " x" + item.getQuantity();
    }

    /** Excel 把长数字改写成的科学计数法，如 2.02611E+17。 */
    private static final Pattern EXCEL_MANGLED_NUMBER = Pattern.compile("[0-9](\\.[0-9]+)?[eE][+-]?[0-9]+");

    /**
     * 订单号、电话、运单号这类“长得像数字”的文本：前面加一个制表符，Excel 打开时按文本显示，
     * 不会把 18 位订单号改成科学计数法丢失精度，也不会去掉电话开头的 0 和 +。
     * 制表符开头同样不会被当成公式；导入时会被自动去掉。其他内容按 {@link #safe} 处理。
     */
    static String excelText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (LOOKS_NUMERIC.matcher(value).matches()) {
            return "\t" + value;
        }
        return safe(value);
    }

    private static final Pattern LOOKS_NUMERIC = Pattern.compile("[+]?[0-9][0-9 ()\\-]*");

    /** 防止 Excel 公式注入（以 = + - @ 开头的单元格前置单引号）。 */
    private static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String decodeCsv(byte[] bytes) {
        int offset = (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GB18030"));
        }
    }

    private static Map<String, String> normalizeHeaders(List<String> rawHeaders) {
        Map<String, String> map = new HashMap<>();
        for (String h : rawHeaders) {
            if (h != null) {
                map.put(h.trim().replace("\uFEFF", "").toLowerCase(Locale.ROOT), h);
            }
        }
        return map;
    }

    private static String findColumn(Map<String, String> normalized, String... aliases) {
        for (String alias : aliases) {
            String found = normalized.get(alias.toLowerCase(Locale.ROOT));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String value(CSVRecord record, String column) {
        if (column == null || !record.isMapped(column)) {
            return "";
        }
        String v = record.get(column);
        return v == null ? "" : v.trim();
    }
}
