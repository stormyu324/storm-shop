package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberAddress;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {
  List<MemberAddress> findByMemberIdAndActiveTrueOrderByPreferredDescIdDesc(Long memberId);
}
