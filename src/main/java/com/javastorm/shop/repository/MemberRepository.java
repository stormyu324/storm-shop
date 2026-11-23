package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<MemberAccount, Long> {
  Optional<MemberAccount> findByEmail(String email);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from MemberAccount m where m.id=:id")
  Optional<MemberAccount> lock(@Param("id") Long id);
}
