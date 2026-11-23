package com.javastorm.shop.repository;

import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.ShippingZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    List<ShippingZone> findByCurrencyOrderByIdAsc(Currency currency);
}
