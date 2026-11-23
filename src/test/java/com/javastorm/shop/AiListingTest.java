package com.javastorm.shop;

import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.ListingAiService;
import com.javastorm.shop.service.ListingAiService.EnglishListing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AiListingTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @MockBean ListingAiService ai;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        when(ai.isEnabled()).thenReturn(true);
    }

    @Test
    void editPageButtonReturnsGeneratedFields() throws Exception {
        when(ai.generate(argThat(in -> in != null && "保温杯".equals(in.name()) && "Steel".equals(in.material()))))
                .thenReturn(new EnglishListing("Insulated Bottle", List.of("KEEPS HOT: 12 hours", "LEAKPROOF: lid"), "Desc"));

        mvc.perform(post("/admin/products/ai/english").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保温杯\",\"material\":\"Steel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleEn").value("Insulated Bottle"))
                .andExpect(jsonPath("$.bulletPointsEn").value("KEEPS HOT: 12 hours\nLEAKPROOF: lid"));

        doThrow(new BusinessException("AI 服务暂时不可用")).when(ai).generate(any());
        mvc.perform(post("/admin/products/ai/english").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保温杯\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AI 服务暂时不可用"));
    }

    @Test
    void batchFillOnlyFillsEmptyFields() throws Exception {
        Product empty = product("A-1", null, null);
        Product partial = product("A-2", null, "MY OWN: bullet");
        Product done = product("A-3", "Already Done", null);
        when(ai.generate(any())).thenReturn(new EnglishListing("Generated Title", List.of("NEW: bullet"), "Generated desc"));

        mvc.perform(post("/admin/export/ai-fill").with(csrf()).param("status", "ON_SALE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", org.hamcrest.Matchers.containsString("已为 2 个商品")));

        Product a = products.findById(empty.getId()).orElseThrow();
        assertThat(a.getTitleEn()).isEqualTo("Generated Title");
        assertThat(a.getBulletPointsEn()).isEqualTo("NEW: bullet");
        assertThat(a.getDescriptionEn()).isEqualTo("Generated desc");

        Product b = products.findById(partial.getId()).orElseThrow();
        assertThat(b.getTitleEn()).isEqualTo("Generated Title");
        assertThat(b.getBulletPointsEn()).isEqualTo("MY OWN: bullet"); // 不覆盖已有卖点

        Product c = products.findById(done.getId()).orElseThrow();
        assertThat(c.getTitleEn()).isEqualTo("Already Done");
        assertThat(c.getDescriptionEn()).isNull(); // 已有英文标题的商品不在本次范围
    }

    @Test
    void batchFillReportsFailures() throws Exception {
        product("F-1", null, null);
        when(ai.generate(any())).thenThrow(new BusinessException("AI 调用太频繁，请稍等一分钟再试"));
        mvc.perform(post("/admin/export/ai-fill").with(csrf()).param("status", "ON_SALE"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("AI 调用太频繁")));
    }

    private Product product(String sku, String titleEn, String bullets) {
        Product p = new Product();
        p.setName("商品" + sku);
        p.setSku(sku);
        p.setPrice(new BigDecimal("10"));
        p.setStock(1);
        p.setStatus(ProductStatus.ON_SALE);
        p.setTitleEn(titleEn);
        p.setBulletPointsEn(bullets);
        return products.save(p);
    }
}
