package com.javastorm.shop.repository;

import com.javastorm.shop.domain.OrderAccess;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderAccessRepository extends JpaRepository<OrderAccess, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderAccess a where a.id = :id")
    Optional<OrderAccess> lock(@Param("id") String id);
}
