package com.javastorm.shop.repository;

import com.javastorm.shop.domain.StaffAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<StaffAccount, Long> {
    Optional<StaffAccount> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StaffAccount s where s.id=:id")
    Optional<StaffAccount> lock(@Param("id") Long id);
}
