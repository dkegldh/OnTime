package com.ontime.meeting.repository;

import com.ontime.meeting.domain.Meeting;
import com.ontime.meeting.domain.MeetingStatus;
import com.ontime.meeting.dto.MeetingSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

  /** 상세 응답에 그룹 이름과 삭제 여부가 필요하므로 그룹을 같이 읽는다. */
  @Query("select m from Meeting m join fetch m.group where m.id = :id")
  Optional<Meeting> findWithGroupById(@Param("id") Long id);

  /**
   * 그룹의 아직 시작하지 않은 회차. "시작 전" = SCHEDULED 이고 약속 일시가 {@code now} 보다 뒤 (정각부터는 시작).
   *
   * <p>{@link AttendanceRepository#deleteUpcoming} 의 서브쿼리와 반드시 같은 조건이어야 한다.
   */
  @Query(
      """
      select m from Meeting m
      where m.group.id = :groupId
        and m.status = :status
        and m.meetingAt > :now
      """)
  List<Meeting> findUpcoming(
      @Param("groupId") Long groupId,
      @Param("status") MeetingStatus status,
      @Param("now") Instant now);

  /** 그룹의 회차를 id 오름차순으로 {@code cursor} 다음부터 조회한다. 요청자의 참석 상태를 같이 담는다. */
  @Query(
      """
      select new com.ontime.meeting.dto.MeetingSummary(
          m.id, m.meetingAt, m.status, m.place.name, a.status)
      from Meeting m
      left join Attendance a on a.meeting = m and a.user.id = :userId
      where m.group.id = :groupId
        and (:cursor is null or m.id > :cursor)
      order by m.id asc
      """)
  List<MeetingSummary> findPageByGroupId(
      @Param("groupId") Long groupId,
      @Param("userId") Long userId,
      @Param("cursor") Long cursor,
      Limit limit);
}
