package com.javastorm.shop.repository;

import com.javastorm.shop.domain.OrderAccess;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface OrderAccessRepository extends JpaRepository<OrderAccess, String> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from OrderAccess a where a.id = :id")
  Optional<OrderAccess> lock(@org.springframework.data.repository.query.Param("id") String id);
}
