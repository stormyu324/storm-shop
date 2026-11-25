package com.javastorm.shop.repository;

import com.javastorm.shop.domain.PaymentReconciliationIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReconciliationRepository extends JpaRepository<PaymentReconciliationIssue, Long> {

    boolean existsByOrderIdAndIssueTypeAndResolvedFalse(Long orderId, String issueType);

    Page<PaymentReconciliationIssue> findByResolvedFalseOrderByIdDesc(Pageable pageable);

    Page<PaymentReconciliationIssue> findAllByOrderByIdDesc(Pageable pageable);
}
