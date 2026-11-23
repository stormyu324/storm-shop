package com.javastorm.shop.repository;

import com.javastorm.shop.domain.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
  Optional<Coupon> findByCode(String code);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coupon c where c.code=:code")
  Optional<Coupon> lockCode(@org.springframework.data.repository.query.Param("code") String code);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coupon c where c.id=:id")
  Optional<Coupon> lock(@org.springframework.data.repository.query.Param("id") Long id);
}
