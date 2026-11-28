package com.javastorm.shop.repository;

import com.javastorm.shop.domain.MemberAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {
    List<MemberAddress> findByMemberIdAndActiveTrueOrderByPreferredDescIdDesc(Long memberId);

    List<MemberAddress> findByMemberId(Long memberId);

    void deleteByMemberId(Long memberId);
}
