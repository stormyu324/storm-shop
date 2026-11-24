package com.javastorm.shop.repository;

import com.javastorm.shop.domain.Category;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 取消或退款时把库存加回去：一条 SQL 原子更新，不会和同时下单扣库存发生乐观锁冲突；
     * 同时递增版本号，正在编辑这个商品的后台页面保存时能发现库存已变。
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :quantity, p.version = p.version + 1 where p.id = :id")
    int restock(@Param("id") Long id, @Param("quantity") int quantity);
}
