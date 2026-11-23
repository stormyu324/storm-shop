package com.javastorm.shop;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.OrderRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.service.VariantService;
import com.javastorm.shop.service.VariantService.Option;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
class VariantTest {

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired ProductService productService;
    @Autowired VariantService variantService;

    Product whiteM, whiteL, blackM, blackL, single;

    @BeforeEach
    void setUp() {
        orders.deleteAll();
        products.deleteAll();
        categories.deleteAll();
        whiteL = variant("TEE-W-L", "White", "L", "59", 5);
        whiteM = variant("TEE-W-M", "White", "M", "59", 5);
        blackM = variant("TEE-B-M", "Black", "M", "69", 5);
        blackL = variant("TEE-B-L", "Black", "L", "69", 0);
        single = new Product();
        single.setName("单品杯子");
        single.setSku("MUG");
        single.setPrice(new BigDecimal("35"));
        single.setStock(3);
        single.setStatus(ProductStatus.ON_SALE);
        single = products.save(single);
    }

    @Test
    void storefrontShowsOneCardPerStyle() {
        List<Product> page = productService.searchStorefront(null, null, PageRequest.of(0, 12)).getContent();
        assertThat(page).extracting(Product::getSku).containsExactlyInAnyOrder("TEE-W-L", "MUG");

        // 按某个变体的 SKU 搜索，显示的就是那个变体
        assertThat(productService.searchStorefront("TEE-B-M", null, PageRequest.of(0, 12)).getContent())
                .extracting(Product::getSku).containsExactly("TEE-B-M");
    }

    @Test
    void familySummaryHasPriceRangeAndStock() {
        var f = variantService.families(List.of(whiteL), Currency.CNY).get("TEE");
        assertThat(f.variantCount()).isEqualTo(4);
        assertThat(f.minPrice()).isEqualByComparingTo("59");
        assertThat(f.priceVaries()).isTrue();
        assertThat(f.inStock()).isTrue();
    }

    @Test
    void optionsKeepOtherDimensionAndOrderSizes() {
        var v = variantService.variants(whiteM);
        assertThat(v.colors()).extracting(Option::label).containsExactly("White", "Black");
        assertThat(v.sizes()).extracting(Option::label).containsExactly("M", "L");

        Option black = v.colors().get(1);
        assertThat(black.targetId()).isEqualTo(blackM.getId()); // 保持尺码 M
        assertThat(black.selected()).isFalse();
        assertThat(v.colors().get(0).selected()).isTrue();

        // 从黑色 M 看：L 码对应黑色 L，已售罄
        Option largeFromBlack = variantService.variants(blackM).sizes().get(1);
        assertThat(largeFromBlack.targetId()).isEqualTo(blackL.getId());
        assertThat(largeFromBlack.available()).isFalse();

        assertThat(variantService.variants(single).isEmpty()).isTrue();
    }

    @Test
    void offShelfVariantsAreNotOffered() {
        blackL.setStatus(ProductStatus.OFF_SHELF);
        products.save(blackL);
        var sizesFromBlack = variantService.variants(blackM).sizes();
        // 黑色只剩 M，选 L 时跳到白色 L
        assertThat(sizesFromBlack).extracting(Option::targetId).containsExactly(blackM.getId(), whiteL.getId());
    }

    @Test
    void pagesRenderPickerAndFromPrice() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("¥59.00 起")))
                .andExpect(content().string(containsString("4 个规格可选")))
                .andExpect(content().string(not(containsString("TEE-B-M"))));
        mvc.perform(get("/products/" + whiteM.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("variant-option selected")))
                .andExpect(content().string(containsString("/products/" + blackM.getId())));
    }

    @Test
    void familyTitleIsCommonPartOfVariantNames() {
        assertThat(VariantService.commonTitle(List.of("纯棉T恤 白色 M码", "纯棉T恤 黑色 L码"))).isEqualTo("纯棉T恤");
        assertThat(VariantService.commonTitle(List.of("Cotton T-Shirt - White, M", "Cotton T-Shirt - Black, L")))
                .isEqualTo("Cotton T-Shirt");
        assertThat(VariantService.commonTitle(List.of("Mug Blue", "Mug Black"))).isEqualTo("Mug");
        assertThat(VariantService.commonTitle(List.of("纯棉T恤白色", "纯棉T恤黑色"))).isEqualTo("纯棉T恤");
        assertThat(VariantService.commonTitle(List.of("Apple", "Banana"))).isNull();
        assertThat(VariantService.commonTitle(List.of("只有一个"))).isNull();
    }

    private Product variant(String sku, String color, String size, String price, int stock) {
        Product p = new Product();
        p.setName("纯棉T恤 " + color + " " + size);
        p.setSku(sku);
        p.setParentSku("TEE");
        p.setColor(color);
        p.setSize(size);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setStatus(ProductStatus.ON_SALE);
        return products.save(p);
    }
}
