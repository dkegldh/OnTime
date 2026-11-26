package com.ontime.meeting.repository;

import com.ontime.meeting.domain.Attendance;
import com.ontime.meeting.domain.MeetingStatus;
import com.ontime.meeting.dto.AttendanceResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

  /** 회차의 멤버별 참석 상태를 등록 순으로. 사용자 정보까지 한 쿼리로 읽어 N+1이 없다. */
  @Query(
      """
      select new com.ontime.meeting.dto.AttendanceResponse(
          u.id, u.nickname, u.profileImageUrl, a.status, a.departedAt, a.arrivedAt)
      from Attendance a join a.user u
      where a.meeting.id = :meetingId
      order by a.id asc
      """)
  List<AttendanceResponse> findResponsesByMeetingId(@Param("meetingId") Long meetingId);

  /** 그룹을 나간 사용자의, 아직 시작하지 않은 회차의 참석 행을 지운다. 지난 회차 기록은 남는다. */
  @Modifying
  @Query(
      """
      delete from Attendance a
      where a.user.id = :userId
        and a.meeting.id in (
          select m.id from Meeting m
          where m.group.id = :groupId
            and m.status = :status
            and m.meetingAt > :now)
      """)
  int deleteUpcoming(
      @Param("groupId") Long groupId,
      @Param("userId") Long userId,
      @Param("status") MeetingStatus status,
      @Param("now") Instant now);
}
