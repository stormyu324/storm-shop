package com.javastorm.shop.repository;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderNo(String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.orderNo = :number")
    Optional<CustomerOrder> findForUpdate(@Param("number") String number);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findForUpdate(@Param("id") Long id);

    Page<CustomerOrder> findByEmailIgnoreCase(String email, Pageable pageable);
    List<CustomerOrder> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime start, LocalDateTime end);

    boolean existsByOrderNo(String orderNo);

    Page<CustomerOrder> findByStatus(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByStatusIn(Collection<OrderStatus> statuses);

    Page<CustomerOrder> findByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    List<CustomerOrder> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime before);
}
