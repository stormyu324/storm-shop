package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证待发货订单批量导出与物流单号 CSV 批量导入发货（含单行失败隔离与公式注入防护）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BulkFulfillmentTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository products;

    @Autowired
    OrderRepository orders;

    @Autowired
    OrderPlacer orderPlacer;

    @Test
    @WithMockUser(username = "support", roles = "SUPPORT")
    void exportToFulfilOrdersAndBulkImportShipmentsWithPartialErrorIsolation() throws Exception {
        Product p = new Product();
        p.setName("批量发货测试商品");
        p.setSku("BULK-" + UUID.randomUUID().toString().substring(0, 8));
        p.setPrice(new BigDecimal("120.00"));
        p.setStock(20);
        p.setStatus(ProductStatus.ON_SALE);
        p = products.save(p);

        CustomerOrder o1 = place(p.getId(), "=Alice", "alice@example.com");
        CustomerOrder o2 = place(p.getId(), "Bob", "bob@example.com");

        // 1) 导出待发货 CSV：包含订单号、商品明细，且对以 = 开头的姓名做公式注入防护
        String exported = mvc.perform(get("/api/admin/orders/fulfillment/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(o1.getOrderNo())))
                .andExpect(content().string(containsString(o2.getOrderNo())))
                .andExpect(content().string(containsString("'=Alice")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(exported).contains(p.getSku());

        // 2) 批量导入 CSV：o1 正常发货，o2 链接非 HTTPS 报错，不存在的订单报错；o1 仍成功发货
        String csv = String.join("\n",
                "订单号,承运商,运单号,物流链接",
                o1.getOrderNo() + ",UPS,1Z999AA10001,https://www.ups.com/track?num=1Z999AA10001",
                o2.getOrderNo() + ",FedEx,FX002,http://insecure.example.com/track",
                "NO-SUCH-ORDER,DHL,DHL003,"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file", "shipments.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/admin/orders/fulfillment/import").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.errors.length()").value(2));

        CustomerOrder reloaded1 = orders.findById(o1.getId()).orElseThrow();
        CustomerOrder reloaded2 = orders.findById(o2.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(reloaded1.getCarrier()).isEqualTo("UPS");
        assertThat(reloaded1.getTrackingNumber()).isEqualTo("1Z999AA10001");
        assertThat(reloaded1.getShippedAt()).isNotNull();
        assertThat(reloaded2.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    private CustomerOrder place(Long productId, String name, String email) {
        Cart cart = new Cart();
        cart.add(productId, 1);
        CheckoutForm form = new CheckoutForm();
        form.setCustomerName(name);
        form.setPhone("13900139000");
        form.setAddress("测试收货地址 88 号");
        form.setCountry("CN");
        form.setEmail(email);
        return orderPlacer.place(cart, form, Currency.CNY, false);
    }
}
