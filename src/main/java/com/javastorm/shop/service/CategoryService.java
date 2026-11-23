package com.javastorm.shop.service;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** 分类的增删改，规则由后台页面和后台接口共用。 */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final ProductRepository products;

    public CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    /** 所有分类及其商品数，按排序。 */
    @Transactional(readOnly = true)
    public Map<Category, Long> listWithCounts() {
        Map<Category, Long> counts = new LinkedHashMap<>();
        for (Category c : categories.findAllByOrderBySortOrderAscIdAsc()) {
            counts.put(c, products.countByCategory(c));
        }
        return counts;
    }

    @Transactional
    public Category create(String name, String nameEn, int sortOrder) {
        String n = checkName(name, null);
        Category c = new Category(n);
        c.setNameEn(nameEn);
        c.setSortOrder(sortOrder);
        return categories.save(c);
    }

    @Transactional
    public Category update(Long id, String name, String nameEn, int sortOrder) {
        Category c = categories.findById(id).orElseThrow(() -> new BusinessException("分类不存在"));
        c.setName(checkName(name, id));
        c.setNameEn(nameEn);
        c.setSortOrder(sortOrder);
        return c;
    }

    /** @return 被删除的分类名称 */
    @Transactional
    public String delete(Long id) {
        Category c = categories.findById(id).orElseThrow(() -> new BusinessException("分类不存在"));
        if (products.countByCategory(c) > 0) {
            throw new BusinessException("分类「" + c.getName() + "」下还有商品，不能删除");
        }
        categories.delete(c);
        return c.getName();
    }

    private String checkName(String name, Long selfId) {
        String n = name == null ? "" : name.trim();
        if (!StringUtils.hasText(n) || n.length() > 50) {
            throw new BusinessException("分类名称需为 1~50 个字");
        }
        categories.findByName(n).filter(o -> !o.getId().equals(selfId)).ifPresent(o -> {
            throw new BusinessException("分类「" + n + "」已存在");
        });
        return n;
    }
}
