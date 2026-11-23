package com.javastorm.shop.domain;

import java.math.BigDecimal;

/** 一组运费参数：首重运费、首重重量、续重每公斤、包邮门槛。 */
public interface ShippingFees {

    BigDecimal getBaseFee();

    int getIncludedGrams();

    BigDecimal getExtraPerKg();

    BigDecimal getFreeThreshold();
}
