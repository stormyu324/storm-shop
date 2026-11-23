package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberFavorite;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberFavoriteRepository extends JpaRepository<MemberFavorite, Long> {
  List<MemberFavorite> findByMemberIdAndActiveTrueOrderByIdDesc(Long memberId);

  Optional<MemberFavorite> findByMemberIdAndProductId(Long memberId, Long productId);
}
