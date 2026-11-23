package com.javastorm.shop.repository;

import com.javastorm.shop.domain.StaffAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface StaffRepository extends JpaRepository<StaffAccount, Long> {
  Optional<StaffAccount> findByUsername(String username);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from StaffAccount s where s.id=:id")
  Optional<StaffAccount> lock(@org.springframework.data.repository.query.Param("id") Long id);
}
