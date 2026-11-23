package com.javastorm.shop.repository;

import com.javastorm.shop.domain.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {}
