package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberFavoriteRepository extends JpaRepository<MemberFavorite, Long> {
    List<MemberFavorite> findByMemberIdAndActiveTrueOrderByIdDesc(Long memberId);

    Optional<MemberFavorite> findByMemberIdAndProductId(Long memberId, Long productId);
}
