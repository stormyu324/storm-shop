package com.javastorm.shop.repository;

import com.javastorm.shop.domain.OrderMail;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderMailRepository extends JpaRepository<OrderMail, Long> {
    boolean existsByEventKey(String eventKey);

    List<OrderMail> findTop100BySentAtIsNullAndNextAttemptBeforeAndAttemptsLessThanOrderByIdAsc(
            LocalDateTime now, int attempts);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OrderMail m where m.id = :id")
    Optional<OrderMail> lock(@Param("id") Long id);
}
