package com.ontime.meeting.controller;

import com.ontime.global.auth.LoginUser;
import com.ontime.global.common.CursorResponse;
import com.ontime.meeting.dto.ArriveRequest;
import com.ontime.meeting.dto.AttendanceResponse;
import com.ontime.meeting.dto.MeetingDetailResponse;
import com.ontime.meeting.dto.MeetingSummary;
import com.ontime.meeting.service.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Meeting", description = "회차와 멤버별 참석 상태")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MeetingController {

  private final MeetingService meetingService;

  @Operation(summary = "그룹의 회차 목록", description = "id 오름차순 커서 페이지네이션. 내 참석 상태를 같이 줍니다.")
  @GetMapping("/meeting-groups/{groupId}/meetings")
  public CursorResponse<MeetingSummary> list(
      @Parameter(hidden = true) @LoginUser Long userId,
      @PathVariable Long groupId,
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
    return meetingService.list(userId, groupId, cursor, size);
  }

  @Operation(summary = "회차 상세", description = "회차에 복사된 장소/규칙과 멤버별 참석 상태")
  @GetMapping("/meetings/{meetingId}")
  public MeetingDetailResponse get(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long meetingId) {
    return meetingService.get(userId, meetingId);
  }

  @Operation(
      summary = "출발",
      description =
          "내 참석 상태를 READY → MOVING 으로 바꿉니다. 약속 3시간 전부터, 약속시간 + 유예 전까지 누를 수 있습니다."
              + " 출발 시각은 서버 시각입니다.")
  @PostMapping("/meetings/{meetingId}/depart")
  public AttendanceResponse depart(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long meetingId) {
    return meetingService.depart(userId, meetingId);
  }

  @Operation(
      summary = "도착",
      description =
          "현재 위치가 약속 장소 반경 안이면 내 참석 상태를 ARRIVED(약속시간 + 유예 전) 또는 LATE_ARRIVED(그 이후)로"
              + " 바꿉니다. 약속 3시간 전부터, 결석 판정 시각 전까지 누를 수 있습니다. 도착 시각과 지각 판정은 서버 시각입니다.")
  @PostMapping("/meetings/{meetingId}/arrive")
  public AttendanceResponse arrive(
      @Parameter(hidden = true) @LoginUser Long userId,
      @PathVariable Long meetingId,
      @Valid @RequestBody ArriveRequest request) {
    return meetingService.arrive(userId, meetingId, request);
  }
}
