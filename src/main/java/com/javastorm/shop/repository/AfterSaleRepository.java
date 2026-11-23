package com.javastorm.shop.repository;

import com.javastorm.shop.domain.AfterSale;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AfterSaleRepository extends JpaRepository<AfterSale, Long> {
    List<AfterSale> findByOrderIdOrderByIdDesc(Long orderId);

    List<AfterSale> findTop20ByStateAndNextAttemptBeforeOrderByIdAsc(String state, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AfterSale a where a.id = :id")
    Optional<AfterSale> lock(@Param("id") Long id);
}
