package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberChallenge;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface MemberChallengeRepository extends JpaRepository<MemberChallenge, String> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from MemberChallenge c where c.id=:id")
  Optional<MemberChallenge> lock(@org.springframework.data.repository.query.Param("id") String id);
}
