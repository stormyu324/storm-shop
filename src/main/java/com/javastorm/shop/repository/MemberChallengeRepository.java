package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberChallengeRepository extends JpaRepository<MemberChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MemberChallenge c where c.id=:id")
    Optional<MemberChallenge> lock(@Param("id") String id);
}
