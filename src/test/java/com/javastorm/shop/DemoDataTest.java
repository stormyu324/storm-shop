package com.javastorm.shop;

import com.javastorm.shop.config.DemoDataInitializer;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDataTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired TransactionTemplate tx;

    DemoDataInitializer initializer;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        initializer = new DemoDataInitializer(categories, products);
    }

    void runInitializer() {
        tx.executeWithoutResult(s -> initializer.run(null));
    }

    @Test
    void demoProductsHaveImagesThatAreServed() throws Exception {
        runInitializer();
        List<Product> all = products.findAll();
        assertThat(all).hasSize(9).allSatisfy(p -> assertThat(p.getMainImage()).startsWith("/img/demo/"));
        assertThat(products.findBySku("DEMO-001-3").orElseThrow().getMainImage()).isEqualTo("/img/demo/tee-black.svg");
        assertThat(products.findBySku("DEMO-001-1").orElseThrow().getMainImage()).isEqualTo("/img/demo/tee-white.svg");
        for (Product p : all) {
            mvc.perform(get(p.getMainImage())).andExpect(status().isOk());
        }
    }

    @Test
    void existingDemoProductsWithoutImagesAreBackfilled() {
        runInitializer();
        // 模拟早期版本的数据：示例商品没有图片；自己改过图片的不动
        Product mug = products.findBySku("DEMO-003").orElseThrow();
        mug.setImageUrls(List.of());
        products.save(mug);
        Product jeans = products.findBySku("DEMO-002").orElseThrow();
        jeans.setImageUrls(List.of("https://example.com/my-jeans.jpg"));
        products.save(jeans);

        runInitializer();

        assertThat(products.findBySku("DEMO-003").orElseThrow().getMainImage()).isEqualTo("/img/demo/mug.svg");
        assertThat(products.findBySku("DEMO-002").orElseThrow().getMainImage()).isEqualTo("https://example.com/my-jeans.jpg");
        assertThat(products.count()).isEqualTo(9);
    }

    @Test
    void emptySearchIsTreatedAsNoSearch() throws Exception {
        runInitializer();
        mvc.perform(get("/").param("q", "  "))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("的搜索结果"))))
                .andExpect(content().string(containsString("class=\"hero\"")));
        mvc.perform(get("/").param("q", " 马克杯 "))
                .andExpect(content().string(containsString("“马克杯”的搜索结果")));
    }
}
