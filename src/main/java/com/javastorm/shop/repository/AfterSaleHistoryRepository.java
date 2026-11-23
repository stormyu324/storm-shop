package com.javastorm.shop.repository;

import com.javastorm.shop.domain.AfterSaleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AfterSaleHistoryRepository extends JpaRepository<AfterSaleHistory, Long> {
    List<AfterSaleHistory> findByCaseIdOrderByIdAsc(Long caseId);
}
