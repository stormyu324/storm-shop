package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车展示数据：按当前币种和商品当前价格计算。
 *
 * @param subtotal      商品金额
 * @param shipping      运费
 * @param total         应付总额 = 商品金额 + 运费
 * @param freeRemaining 还差多少可以包邮，没有包邮门槛或已包邮时为 null
 */
public record CartView(List<Line> lines, BigDecimal subtotal, BigDecimal shipping, BigDecimal total,
                       Currency currency, BigDecimal freeRemaining) {

    public record Line(Product product, int quantity, BigDecimal unitPrice) {
        public BigDecimal getSubtotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        public boolean isAvailable() {
            return product.isOnSale() && product.getStock() >= quantity;
        }
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public boolean isAllAvailable() {
        return lines.stream().allMatch(Line::isAvailable);
    }
}
