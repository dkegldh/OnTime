package com.ontime.group.repository;

import com.ontime.group.domain.InviteCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

  @Query("select c from InviteCode c where c.group.id = :groupId")
  Optional<InviteCode> findByGroupId(@Param("groupId") Long groupId);

  /** 참여할 때 그룹(삭제 여부)도 같이 보므로 fetch join 한다. */
  @Query("select c from InviteCode c join fetch c.group where c.code = :code")
  Optional<InviteCode> findWithGroupByCode(@Param("code") String code);

  boolean existsByCode(String code);
}
