package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ShippingService;
import com.javastorm.shop.service.ShippingService.Quote;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired ShippingRuleRepository rules;
    @Autowired OrderService orderService;

    Product heavy;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        rules.deleteAll();
        heavy = new Product();
        heavy.setName("铸铁锅");
        heavy.setSku("POT-1");
        heavy.setPrice(new BigDecimal("100.00"));
        heavy.setPriceUsd(new BigDecimal("15.00"));
        heavy.setWeightGrams(1150);
        heavy.setStock(10);
        heavy.setStatus(ProductStatus.ON_SALE);
        heavy = products.save(heavy);
    }

    @org.junit.jupiter.api.AfterEach
    void removeRules() {
        rules.deleteAll(); // 别影响其他测试类的金额
    }

    static ShippingRule usdRule() {
        ShippingRule r = new ShippingRule(Currency.USD);
        r.setEnabled(true);
        r.setBaseFee(new BigDecimal("5.99"));
        r.setIncludedGrams(1000);
        r.setExtraPerKg(new BigDecimal("2.00"));
        r.setFreeThreshold(new BigDecimal("49.00"));
        return r;
    }

    @Test
    void calculatesBaseExtraWeightAndFreeThreshold() {
        ShippingRule r = usdRule();
        // 2300 克：超出 1300 克按 2 公斤
        Quote q = ShippingService.calculate(r, new BigDecimal("30.00"), 2300);
        assertThat(q.fee()).isEqualByComparingTo("9.99");
        assertThat(q.freeRemaining()).isEqualByComparingTo("19.00");

        assertThat(ShippingService.calculate(r, new BigDecimal("30.00"), 900).fee()).isEqualByComparingTo("5.99");
        assertThat(ShippingService.calculate(r, new BigDecimal("49.00"), 9000).isFree()).isTrue();
        assertThat(ShippingService.calculate(r, BigDecimal.ZERO, 0).isFree()).isTrue();

        assertThat(ShippingService.calculate(null, new BigDecimal("1"), 5000).isFree()).isTrue();

        ShippingRule flat = usdRule();
        flat.setExtraPerKg(null);
        flat.setFreeThreshold(null);
        Quote f = ShippingService.calculate(flat, new BigDecimal("100"), 50000);
        assertThat(f.fee()).isEqualByComparingTo("5.99");
        assertThat(f.freeRemaining()).isNull();
    }

    @Test
    void orderIncludesShippingInTotal() {
        rules.save(usdRule());
        Cart cart = new Cart();
        cart.add(heavy.getId(), 2); // $30, 2300 克
        CustomerOrder order = orderService.placeOrder(cart, checkout(), Currency.USD);
        assertThat(order.getShippingFee()).isEqualByComparingTo("9.99");
        assertThat(order.getItemsTotal()).isEqualByComparingTo("30.00");
        assertThat(order.getTotal()).isEqualByComparingTo("39.99");

        // 人民币没有设置规则：免运费
        Cart cny = new Cart();
        cny.add(heavy.getId(), 1);
        CustomerOrder cnyOrder = orderService.placeOrder(cny, checkout(), Currency.CNY);
        assertThat(cnyOrder.getShippingFee()).isZero();
        assertThat(cnyOrder.getTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void cartShowsShippingAndFreeShippingHint() throws Exception {
        rules.save(usdRule());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(csrf()).param("lang", "en")
                .param("productId", heavy.getId().toString()).param("quantity", "2"));
        mvc.perform(get("/cart").session(session).param("lang", "en"))
                .andExpect(content().string(containsString("$9.99")))
                .andExpect(content().string(containsString("Add $19.00 more for free shipping")))
                .andExpect(content().string(containsString("$39.99")));

        mvc.perform(post("/cart/add").session(session).with(csrf()).param("lang", "en")
                .param("productId", heavy.getId().toString()).param("quantity", "2"));
        mvc.perform(get("/checkout").session(session).param("lang", "en"))
                .andExpect(content().string(containsString("Free")))
                .andExpect(content().string(containsString("$60.00")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSavesRule() throws Exception {
        mvc.perform(post("/admin/shipping/USD").with(csrf()).param("enabled", "true").param("baseFee", "4.5")
                        .param("includedGrams", "500").param("extraPerKg", "").param("freeThreshold", "39"))
                .andExpect(redirectedUrl("/admin/shipping"));
        ShippingRule r = rules.findByCurrency(Currency.USD).orElseThrow();
        assertThat(r.isEnabled()).isTrue();
        assertThat(r.getBaseFee()).isEqualByComparingTo("4.5");
        assertThat(r.getIncludedGrams()).isEqualTo(500);
        assertThat(r.getExtraPerKg()).isNull();
        assertThat(r.getFreeThreshold()).isEqualByComparingTo("39");

        mvc.perform(get("/admin/shipping")).andExpect(content().string(containsString("美元（英文前台）")));
    }

    private static CheckoutForm checkout() {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("John");
        f.setPhone("+1 415 555 0100");
        f.setAddress("SF");
        return f;
    }
}
