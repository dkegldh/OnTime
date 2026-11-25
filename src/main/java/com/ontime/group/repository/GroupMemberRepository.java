package com.ontime.group.repository;

import com.ontime.group.domain.GroupMember;
import com.ontime.group.dto.MeetingGroupSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

  @Query(
      """
      select m from GroupMember m
      where m.group.id = :groupId and m.user.id = :userId
      """)
  Optional<GroupMember> findByGroupIdAndUserId(
      @Param("groupId") Long groupId, @Param("userId") Long userId);

  /** 사용자가 속한, 삭제되지 않은 그룹을 id 내림차순으로 {@code cursor} 다음부터 조회한다. */
  @Query(
      """
      select new com.ontime.group.dto.MeetingGroupSummary(
          g.id, g.name, g.type, g.place.name, g.startDate, g.meetingTime, m.role)
      from GroupMember m join m.group g
      where m.user.id = :userId
        and g.deletedAt is null
        and (:cursor is null or g.id < :cursor)
      order by g.id desc
      """)
  List<MeetingGroupSummary> findPageByUserId(
      @Param("userId") Long userId, @Param("cursor") Long cursor, Limit limit);
}
