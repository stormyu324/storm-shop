package com.javastorm.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 按国家 / 地区单独设置的运费，例如“美国、加拿大”一组、“欧洲”一组。
 * 列出的国家用这里的运费，没列出的国家用该币种的默认规则（{@link ShippingRule}）。
 */
@Entity
public class ShippingZone implements ShippingFees {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false, length = 50)
    private String name;

    /** ISO 3166 两位国家代码，逗号分隔，例如 "US,CA"。 */
    @Column(nullable = false, length = 1000)
    private String countries;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFee = BigDecimal.ZERO;

    private int includedGrams = 1000;

    @Column(precision = 12, scale = 2)
    private BigDecimal extraPerKg;

    @Column(precision = 12, scale = 2)
    private BigDecimal freeThreshold;

    protected ShippingZone() {
    }

    public ShippingZone(Currency currency) {
        this.currency = currency;
    }

    public List<String> getCountryList() {
        return Arrays.stream(countries.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean covers(String country) {
        return country != null && getCountryList().contains(country.toUpperCase(Locale.ROOT));
    }

    public Long getId() {
        return id;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountries() {
        return countries;
    }

    public void setCountries(List<String> codes) {
        this.countries = String.join(",", codes);
    }

    @Override
    public BigDecimal getBaseFee() {
        return baseFee;
    }

    public void setBaseFee(BigDecimal baseFee) {
        this.baseFee = baseFee == null ? BigDecimal.ZERO : baseFee;
    }

    @Override
    public int getIncludedGrams() {
        return includedGrams;
    }

    public void setIncludedGrams(int includedGrams) {
        this.includedGrams = Math.max(0, includedGrams);
    }

    @Override
    public BigDecimal getExtraPerKg() {
        return extraPerKg;
    }

    public void setExtraPerKg(BigDecimal extraPerKg) {
        this.extraPerKg = extraPerKg;
    }

    @Override
    public BigDecimal getFreeThreshold() {
        return freeThreshold;
    }

    public void setFreeThreshold(BigDecimal freeThreshold) {
        this.freeThreshold = freeThreshold;
    }
}
