package com.javastorm.shop.repository;

import com.javastorm.shop.domain.OrderMail;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface OrderMailRepository extends JpaRepository<OrderMail, Long> {
  boolean existsByEventKey(String eventKey);

  List<OrderMail> findTop20BySentAtIsNullAndNextAttemptBeforeAndAttemptsLessThanOrderByIdAsc(
      LocalDateTime now, int attempts);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from OrderMail m where m.id = :id")
  Optional<OrderMail> lock(@org.springframework.data.repository.query.Param("id") Long id);
}
