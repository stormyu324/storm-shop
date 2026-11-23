package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.domain.ShippingRule;
import com.javastorm.shop.domain.ShippingZone;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.repository.ShippingRuleRepository;
import com.javastorm.shop.repository.ShippingZoneRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.Countries;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ShippingService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingZoneTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired ShippingRuleRepository rules;
    @Autowired ShippingZoneRepository zones;
    @Autowired ShippingService shipping;
    @Autowired OrderService orderService;

    Product item;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        zones.deleteAll();
        rules.deleteAll();
        item = new Product();
        item.setName("铸铁锅");
        item.setSku("POT-Z");
        item.setPrice(new BigDecimal("100.00"));
        item.setPriceUsd(new BigDecimal("15.00"));
        item.setWeightGrams(1150);
        item.setStock(10);
        item.setStatus(ProductStatus.ON_SALE);
        item = products.save(item);

        // 美国：$4.99，满 $39 包邮；欧洲：$12.99 + 每公斤 $4；其他国家默认 $19.99
        zones.save(zone("美国", List.of("US"), "4.99", null, "39"));
        zones.save(zone("欧洲", List.of("DE", "FR", "GB"), "12.99", "4", null));
        ShippingRule def = new ShippingRule(Currency.USD);
        def.setEnabled(true);
        def.setBaseFee(new BigDecimal("19.99"));
        rules.save(def);
    }

    @AfterEach
    void cleanUp() {
        zones.deleteAll();
        rules.deleteAll();
    }

    static ShippingZone zone(String name, List<String> countries, String base, String perKg, String free) {
        ShippingZone z = new ShippingZone(Currency.USD);
        z.setName(name);
        z.setCountries(countries);
        z.setBaseFee(new BigDecimal(base));
        z.setIncludedGrams(1000);
        z.setExtraPerKg(perKg == null ? null : new BigDecimal(perKg));
        z.setFreeThreshold(free == null ? null : new BigDecimal(free));
        return z;
    }

    @Test
    void feeDependsOnDestination() {
        BigDecimal items = new BigDecimal("30.00");
        var us = shipping.quote(Currency.USD, "US", items, 2300);
        assertThat(us.fee()).isEqualByComparingTo("4.99");
        assertThat(us.freeRemaining()).isEqualByComparingTo("9.00");
        assertThat(shipping.quote(Currency.USD, "de", items, 2300).fee()).isEqualByComparingTo("20.99");
        assertThat(shipping.quote(Currency.USD, "BR", items, 2300).fee()).isEqualByComparingTo("19.99");
        // 人民币没有任何设置：免运费
        assertThat(shipping.quote(Currency.CNY, "US", items, 2300).isFree()).isTrue();
    }

    @Test
    void zonesOnlyBlocksOtherCountries() {
        ShippingRule def = rules.findByCurrency(Currency.USD).orElseThrow();
        def.setZonesOnly(true);
        rules.save(def);

        assertThat(shipping.quote(Currency.USD, "BR", BigDecimal.TEN, 100).deliverable()).isFalse();
        assertThat(shipping.quote(Currency.USD, "US", BigDecimal.TEN, 100).deliverable()).isTrue();

        Cart cart = new Cart();
        cart.add(item.getId(), 1);
        CheckoutForm form = form("BR");
        assertThatThrownBy(() -> orderService.placeOrder(cart, form, Currency.USD))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不配送");
        assertThat(products.findById(item.getId()).orElseThrow().getStock()).isEqualTo(10);
    }

    @Test
    void orderStoresCountryAndZoneFee() {
        Cart cart = new Cart();
        cart.add(item.getId(), 2); // $30, 2300 克
        CustomerOrder order = orderService.placeOrder(cart, form("GB"), Currency.USD);
        assertThat(order.getCountry()).isEqualTo("GB");
        assertThat(order.getShippingFee()).isEqualByComparingTo("20.99");
        assertThat(order.getTotal()).isEqualByComparingTo("50.99");
    }

    @Test
    void checkoutRecalculatesWhenCountryChanges() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(csrf()).param("lang", "en")
                .param("productId", item.getId().toString()).param("quantity", "2"));

        // 默认按美国估算，下拉框预选美国
        mvc.perform(get("/checkout").session(session).param("lang", "en"))
                .andExpect(content().string(containsString("<option value=\"US\" selected=\"selected\">United States</option>")))
                .andExpect(content().string(containsString("$4.99")));

        mvc.perform(get("/checkout/shipping").session(session).param("country", "DE").param("lang", "en"))
                .andExpect(jsonPath("$.shipping").value("$20.99"))
                .andExpect(jsonPath("$.total").value("$50.99"))
                .andExpect(jsonPath("$.deliverable").value(true));

        // 选择会被记住：购物车按德国显示
        mvc.perform(get("/cart").session(session).param("lang", "en"))
                .andExpect(content().string(containsString("to Germany")))
                .andExpect(content().string(containsString("$20.99")));

        ShippingRule def = rules.findByCurrency(Currency.USD).orElseThrow();
        def.setZonesOnly(true);
        rules.save(def);
        mvc.perform(get("/checkout/shipping").session(session).param("country", "BR").param("lang", "en"))
                .andExpect(jsonPath("$.deliverable").value(false))
                .andExpect(jsonPath("$.notice").value("Sorry, we don't ship to Brazil yet"));

        // 直接提交被拒绝，不会生成订单
        mvc.perform(post("/checkout").session(session).with(csrf()).param("lang", "en")
                        .param("customerName", "Ana").param("phone", "+55 11 5555 0100").param("address", "São Paulo")
                        .param("country", "BR"))
                .andExpect(content().string(containsString("Sorry, we don&#39;t ship to Brazil yet")));
        assertThat(orders.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminManagesZonesAndRejectsOverlapsAndBadCodes() throws Exception {
        mvc.perform(post("/admin/shipping/USD/zones").with(csrf()).param("name", "北美")
                        .param("countries", "ca, us").param("baseFee", "6"))
                .andExpect(flash().attribute("error", containsString("US 已经在分组「美国」里了")));
        mvc.perform(post("/admin/shipping/USD/zones").with(csrf()).param("name", "坏代码")
                        .param("countries", "XX").param("baseFee", "6"))
                .andExpect(flash().attribute("error", containsString("国家代码有误")));

        mvc.perform(post("/admin/shipping/USD/zones").with(csrf()).param("name", "加拿大 澳洲")
                        .param("countries", "ca，AU").param("baseFee", "8.5").param("includedGrams", "500"))
                .andExpect(redirectedUrl("/admin/shipping"));
        ShippingZone added = zones.findByCurrencyOrderByIdAsc(Currency.USD).get(2);
        assertThat(added.getCountryList()).containsExactly("CA", "AU");
        assertThat(added.getIncludedGrams()).isEqualTo(500);

        mvc.perform(get("/admin/shipping")).andExpect(content().string(containsString("加拿大、澳大利亚")));

        mvc.perform(post("/admin/shipping/zones/{id}/delete", added.getId()).with(csrf()))
                .andExpect(redirectedUrl("/admin/shipping"));
        assertThat(zones.findByCurrencyOrderByIdAsc(Currency.USD)).hasSize(2);
    }

    @Test
    void countryHelpers() {
        assertThat(Countries.parse("us, ca；DE  gb")).containsExactly("US", "CA", "DE", "GB");
        assertThat(Countries.parse("US, ZZ")).isNull();
        assertThat(Countries.name("DE", Locale.SIMPLIFIED_CHINESE)).isEqualTo("德国");
        assertThat(Countries.list(Locale.ENGLISH).get(0).code()).isEqualTo("US");
        assertThat(Countries.list(Locale.ENGLISH)).hasSizeGreaterThan(200);
    }

    private static CheckoutForm form(String country) {
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("John");
        f.setPhone("+1 415 555 0100");
        f.setAddress("Somewhere");
        f.setCountry(country);
        return f;
    }
}
