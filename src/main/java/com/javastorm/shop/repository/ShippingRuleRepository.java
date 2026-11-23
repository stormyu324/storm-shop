package com.javastorm.shop.repository;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShippingRuleRepository extends JpaRepository<ShippingRule, Long> {

    Optional<ShippingRule> findByCurrency(Currency currency);
}
