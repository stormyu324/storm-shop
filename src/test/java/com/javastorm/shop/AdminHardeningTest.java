package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.ImageStorageService;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.web.form.CheckoutForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 订单状态流转、后台非法参数、图片清理等。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminHardeningTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired ProductService productService;
    @Autowired ImageStorageService images;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
    }

    @Test
    void orderStatusFollowsAllowedTransitions() {
        Product p = product("FLOW-1", 10);
        CustomerOrder order = placeOrder(p, 2);

        orderService.changeStatus(order.getId(), OrderStatus.SHIPPED);
        assertThatThrownBy(() -> orderService.changeStatus(order.getId(), OrderStatus.PLACED))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能改为");
        orderService.changeStatus(order.getId(), OrderStatus.COMPLETED);
        assertThatThrownBy(() -> orderService.changeStatus(order.getId(), OrderStatus.CANCELLED))
                .isInstanceOf(BusinessException.class);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(8);

        // 已发货的订单可以取消（退货），库存退回
        CustomerOrder returned = placeOrder(p, 3);
        orderService.changeStatus(returned.getId(), OrderStatus.SHIPPED);
        orderService.changeStatus(returned.getId(), OrderStatus.CANCELLED);
        assertThat(products.findById(p.getId()).orElseThrow().getStock()).isEqualTo(8);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidAdminParametersDoNotCauseServerErrors() throws Exception {
        Product p = product("BAD-1", 1);
        mvc.perform(post("/api/admin/products/batch").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + p.getId() + "],\"action\":\"HACK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.action").exists());
        mvc.perform(get("/api/admin/export/readiness").param("status", "HACK")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/export/amazon.csv").param("status", "HACK")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/orders").param("page", "-5")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletingProductRemovesImageFilesNoLongerUsed() throws Exception {
        MockMultipartFile image = new MockMultipartFile("files", "a.png", "image/png", Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII="));
        String uploaded = mvc.perform(multipart("/api/admin/images").file(image).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        String imageUrl = uploaded.replaceAll(".*\\[\"([^\"]+)\"].*", "$1");
        mvc.perform(post("/api/admin/products").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"带图商品\",\"price\":10,\"stock\":1,\"imageUrls\":[\"" + imageUrl + "\"]}"))
                .andExpect(status().isCreated());
        Product original = products.findAll().get(0);
        String url = original.getMainImage();
        Path file = images.getRoot().resolve(url.substring(ImageStorageService.URL_PREFIX.length()));
        assertThat(file).exists();

        // 复制出来的商品共用同一张图：删掉原商品后文件必须保留
        Product copy = productService.duplicate(original.getId());
        productService.delete(List.of(original.getId()));
        assertThat(file).exists();

        // 最后一个引用也删掉后，文件被清理
        productService.delete(List.of(copy.getId()));
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void cartQuantityDoesNotOverflow() {
        Cart cart = new Cart();
        cart.add(1L, 5);
        cart.add(1L, Integer.MAX_VALUE);
        assertThat(cart.getItems().get(1L)).isEqualTo(Cart.MAX_QUANTITY);
    }

    private CustomerOrder placeOrder(Product p, int qty) {
        Cart cart = new Cart();
        cart.add(p.getId(), qty);
        CheckoutForm f = new CheckoutForm();
        f.setCustomerName("张三");
        f.setPhone("13800000000");
        f.setAddress("北京市");
        return orderService.placeOrder(cart, f, Currency.CNY);
    }

    private Product product(String sku, int stock) {
        Product p = new Product();
        p.setName("商品" + sku);
        p.setSku(sku);
        p.setPrice(new BigDecimal("10.00"));
        p.setStock(stock);
        p.setStatus(ProductStatus.ON_SALE);
        return products.save(p);
    }
}
