package com.javastorm.shop.repository;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySku(String sku);

    List<Product> findByParentSkuInAndStatus(Collection<String> parentSkus, ProductStatus status);

    List<Product> findByParentSkuAndStatusOrderByIdAsc(String parentSku, ProductStatus status);

    boolean existsBySku(String sku);

    long countByStatus(ProductStatus status);

    long countByCategory(Category category);

    long countByStockLessThanEqualAndStatus(int stock, ProductStatus status);

    /** 有多少商品引用了这张图片（复制出来的商品会共用图片）。 */
    @Query("select count(p) from Product p join p.imageUrls u where u = :url")
    long countUsingImage(@Param("url") String url);
}
