package com.javastorm.shop.repository;

import com.javastorm.shop.domain.AfterSaleHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AfterSaleHistoryRepository extends JpaRepository<AfterSaleHistory, Long> {
  List<AfterSaleHistory> findByCaseIdOrderByIdAsc(Long caseId);
}
