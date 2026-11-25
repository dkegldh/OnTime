package com.ontime.group.controller;

import com.ontime.global.auth.LoginUser;
import com.ontime.global.common.CursorResponse;
import com.ontime.group.dto.CreateMeetingGroupRequest;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.dto.MeetingGroupSummary;
import com.ontime.group.dto.UpdateMeetingGroupRequest;
import com.ontime.group.service.MeetingGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Meeting Group", description = "약속 그룹")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/meeting-groups")
public class MeetingGroupController {

  private final MeetingGroupService meetingGroupService;

  @Operation(summary = "일회성 약속 그룹 생성", description = "요청한 사용자가 방장이 됩니다.")
  @PostMapping
  public ResponseEntity<MeetingGroupResponse> create(
      @Parameter(hidden = true) @LoginUser Long userId,
      @Valid @RequestBody CreateMeetingGroupRequest request) {
    MeetingGroupResponse response = meetingGroupService.create(userId, request);
    return ResponseEntity.created(URI.create("/api/v1/meeting-groups/" + response.id()))
        .body(response);
  }

  @Operation(summary = "내가 속한 그룹 목록", description = "id 내림차순 커서 페이지네이션")
  @GetMapping
  public CursorResponse<MeetingGroupSummary> list(
      @Parameter(hidden = true) @LoginUser Long userId,
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
    return meetingGroupService.list(userId, cursor, size);
  }

  @Operation(summary = "그룹 상세")
  @GetMapping("/{groupId}")
  public MeetingGroupResponse get(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long groupId) {
    return meetingGroupService.get(userId, groupId);
  }

  @Operation(summary = "그룹 이름 수정")
  @PatchMapping("/{groupId}")
  public MeetingGroupResponse update(
      @Parameter(hidden = true) @LoginUser Long userId,
      @PathVariable Long groupId,
      @Valid @RequestBody UpdateMeetingGroupRequest request) {
    return meetingGroupService.update(userId, groupId, request);
  }

  @Operation(summary = "그룹 삭제", description = "soft delete. 삭제된 그룹은 조회되지 않습니다.")
  @DeleteMapping("/{groupId}")
  public ResponseEntity<Void> delete(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long groupId) {
    meetingGroupService.delete(userId, groupId);
    return ResponseEntity.noContent().build();
  }
}
