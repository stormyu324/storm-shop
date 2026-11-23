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

    boolean existsByOrderNo(String orderNo);

    Page<CustomerOrder> findByStatus(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByStatusIn(Collection<OrderStatus> statuses);

    Page<CustomerOrder> findByStatusIn(Collection<OrderStatus> statuses, Pageable pageable);

    List<CustomerOrder> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime before);
}
