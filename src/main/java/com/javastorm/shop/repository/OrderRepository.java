package com.javastorm.shop.repository;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderNo(String orderNo);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from CustomerOrder o where o.orderNo = :number")
    Optional<CustomerOrder> findForUpdate(@org.springframework.data.repository.query.Param("number") String number);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    org.springframework.data.domain.Page<CustomerOrder> findByEmailIgnoreCase(String email, org.springframework.data.domain.Pageable pageable);
    java.util.List<CustomerOrder> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.LocalDateTime start, java.time.LocalDateTime end);

    boolean existsByOrderNo(String orderNo);

    Page<CustomerOrder> findByStatus(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByStatusIn(Collection<OrderStatus> statuses);

    Page<CustomerOrder> findByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    List<CustomerOrder> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime before);
}
