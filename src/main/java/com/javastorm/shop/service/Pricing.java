package com.javastorm.shop.service;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * 计算商品在不同币种下的价格。美元价格优先用商品上填写的“美元售价”，
 * 没填时按 shop.cny-per-usd 汇率从人民币换算。模板里通过 ${@pricing} 调用。
 */
@Component("pricing")
public class Pricing {

    private final BigDecimal cnyPerUsd;

    public Pricing(@Value("${shop.cny-per-usd}") BigDecimal cnyPerUsd) {
        if (cnyPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("shop.cny-per-usd 必须大于 0");
        }
        this.cnyPerUsd = cnyPerUsd;
    }

    public BigDecimal price(Product p, Currency currency) {
        if (currency == Currency.CNY) {
            return p.getPrice();
        }
        return p.getPriceUsd() != null ? p.getPriceUsd() : toUsd(p.getPrice());
    }

    /** 划线价：美元按与售价相同的比例换算，保证折扣幅度一致。 */
    public BigDecimal originalPrice(Product p, Currency currency) {
        if (!p.hasDiscount()) {
            return null;
        }
        if (currency == Currency.CNY) {
            return p.getOriginalPrice();
        }
        if (p.getPriceUsd() == null || p.getPrice().signum() == 0) {
            return toUsd(p.getOriginalPrice());
        }
        return p.getOriginalPrice().multiply(p.getPriceUsd())
                .divide(p.getPrice(), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal toUsd(BigDecimal cny) {
        return cny.divide(cnyPerUsd, 2, RoundingMode.HALF_UP);
    }

    public String format(BigDecimal amount, Currency currency) {
        if (amount == null) {
            return "";
        }
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return currency.getSymbol() + f.format(amount);
    }

    public String formatPrice(Product p, Currency currency) {
        return format(price(p, currency), currency);
    }

    public String formatOriginalPrice(Product p, Currency currency) {
        return format(originalPrice(p, currency), currency);
    }
}
