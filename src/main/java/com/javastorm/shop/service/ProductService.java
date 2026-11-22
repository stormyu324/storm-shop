package com.javastorm.shop.service;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.web.form.ProductForm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ProductService {

    private static final DateTimeFormatter SKU_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ImageStorageService images;

    public ProductService(ProductRepository products, CategoryRepository categories, ImageStorageService images) {
        this.products = products;
        this.categories = categories;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String keyword, ProductStatus status, Long categoryId, Pageable pageable) {
        return products.findAll(spec(keyword, status, categoryId), pageable);
    }

    /** 导出用：不分页，按 ID 排序。 */
    @Transactional(readOnly = true)
    public List<Product> list(ProductStatus status, Long categoryId) {
        return products.findAll(spec(null, status, categoryId), org.springframework.data.domain.Sort.by("id"));
    }

    @Transactional(readOnly = true)
    public Page<Product> searchStorefront(String keyword, Long categoryId, Pageable pageable) {
        return search(keyword, ProductStatus.ON_SALE, categoryId, pageable);
    }

    @Transactional(readOnly = true)
    public Product get(Long id) {
        return products.findById(id).orElseThrow(() -> new BusinessException("商品不存在"));
    }

    /** 新建或更新商品。上传的图片追加在已填写的图片地址之后。 */
    @Transactional
    public Product save(ProductForm form, List<MultipartFile> uploads) {
        Product product = form.getId() == null ? new Product() : get(form.getId());
        if (form.getId() != null && form.getVersion() != null && form.getVersion() != product.getVersion()) {
            // 打开编辑页之后商品被改过（例如顾客下单扣了库存），直接保存会把库存等改动覆盖掉
            throw new BusinessException("这个商品在你编辑期间被修改过（可能有新订单扣减了库存），"
                    + "为避免覆盖，请重新打开编辑页再修改");
        }

        String sku = StringUtils.hasText(form.getSku()) ? form.getSku().trim() : generateSku();
        products.findBySku(sku)
                .filter(other -> !other.getId().equals(product.getId()))
                .ifPresent(other -> {
                    throw new BusinessException("SKU「" + sku + "」已被商品「" + other.getName() + "」使用");
                });

        List<String> imageUrls = new ArrayList<>(form.imageUrlList());
        imageUrls.addAll(images.storeAll(uploads));

        product.setName(form.getName().trim());
        product.setSku(sku);
        product.setCategory(form.getCategoryId() == null ? null
                : categories.findById(form.getCategoryId()).orElse(null));
        product.setPrice(form.getPrice());
        product.setOriginalPrice(form.getOriginalPrice());
        product.setStock(form.getStock());
        product.setDescription(form.getDescription());
        product.setStatus(form.getStatus());
        product.setImageUrls(imageUrls);
        product.setParentSku(blankToNull(form.getParentSku()));
        product.setTitleEn(blankToNull(form.getTitleEn()));
        product.setDescriptionEn(blankToNull(form.getDescriptionEn()));
        product.setBulletPointsEn(blankToNull(form.getBulletPointsEn()));
        product.setBrand(blankToNull(form.getBrand()));
        product.setColor(blankToNull(form.getColor()));
        product.setSize(blankToNull(form.getSize()));
        product.setMaterial(blankToNull(form.getMaterial()));
        product.setWeightGrams(form.getWeightGrams());
        product.setLengthCm(form.getLengthCm());
        product.setWidthCm(form.getWidthCm());
        product.setHeightCm(form.getHeightCm());
        product.setBarcode(blankToNull(form.getBarcode()));
        product.setPriceUsd(form.getPriceUsd());
        return products.save(product);
    }

    @Transactional
    public int changeStatus(Collection<Long> ids, ProductStatus status) {
        List<Product> list = products.findAllById(ids);
        list.forEach(p -> p.setStatus(status));
        return list.size();
    }

    @Transactional
    public int delete(Collection<Long> ids) {
        List<Product> list = products.findAllById(ids);
        products.deleteAll(list);
        return list.size();
    }

    /** 复制一个商品作为草稿，方便上架同款不同规格的商品。 */
    @Transactional
    public Product duplicate(Long id) {
        Product source = get(id);
        Product copy = new Product();
        copy.setName(source.getName() + "（副本）");
        copy.setSku(generateSku());
        copy.setCategory(source.getCategory());
        copy.setPrice(source.getPrice());
        copy.setOriginalPrice(source.getOriginalPrice());
        copy.setStock(source.getStock());
        copy.setDescription(source.getDescription());
        copy.setStatus(ProductStatus.DRAFT);
        copy.setImageUrls(source.getImageUrls());
        // 同款的变体通常共用款号、品牌、材质和英文资料，颜色/尺码/条码需要重新填写
        copy.setParentSku(source.getParentSku());
        copy.setTitleEn(source.getTitleEn());
        copy.setDescriptionEn(source.getDescriptionEn());
        copy.setBulletPointsEn(source.getBulletPointsEn());
        copy.setBrand(source.getBrand());
        copy.setColor(source.getColor());
        copy.setSize(source.getSize());
        copy.setMaterial(source.getMaterial());
        copy.setWeightGrams(source.getWeightGrams());
        copy.setLengthCm(source.getLengthCm());
        copy.setWidthCm(source.getWidthCm());
        copy.setHeightCm(source.getHeightCm());
        copy.setPriceUsd(source.getPriceUsd());
        return products.save(copy);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String generateSku() {
        String sku;
        do {
            sku = "P" + LocalDateTime.now().format(SKU_TIME)
                    + String.format(Locale.ROOT, "%03d", ThreadLocalRandom.current().nextInt(1000));
        } while (products.existsBySku(sku));
        return sku;
    }

    private static Specification<Product> spec(String keyword, ProductStatus status, Long categoryId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("sku")), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.<Category>get("category").get("id"), categoryId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
