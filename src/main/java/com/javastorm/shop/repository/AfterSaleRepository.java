package com.javastorm.shop.repository;

import com.javastorm.shop.domain.AfterSale;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface AfterSaleRepository extends JpaRepository<AfterSale, Long> {
  List<AfterSale> findByOrderIdOrderByIdDesc(Long orderId);

  List<AfterSale> findTop20ByStateAndNextAttemptBeforeOrderByIdAsc(String state, LocalDateTime now);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AfterSale a where a.id = :id")
  Optional<AfterSale> lock(@org.springframework.data.repository.query.Param("id") Long id);
}
