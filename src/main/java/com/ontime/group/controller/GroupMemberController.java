package com.ontime.group.controller;

import com.ontime.global.auth.LoginUser;
import com.ontime.global.common.CursorResponse;
import com.ontime.group.dto.GroupMemberSummary;
import com.ontime.group.dto.InviteCodeResponse;
import com.ontime.group.dto.JoinGroupRequest;
import com.ontime.group.dto.MeetingGroupResponse;
import com.ontime.group.service.GroupMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Group Member", description = "초대 코드, 참여, 나가기, 멤버 목록")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/meeting-groups")
public class GroupMemberController {

  private final GroupMemberService groupMemberService;

  @Operation(summary = "초대 코드 발급", description = "방장만 할 수 있습니다. 다시 발급하면 이전 코드는 바로 무효가 됩니다. 유효기간 7일.")
  @PostMapping("/{groupId}/invite-codes")
  public ResponseEntity<InviteCodeResponse> issueInviteCode(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long groupId) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(groupMemberService.issueInviteCode(userId, groupId));
  }

  @Operation(summary = "초대 코드로 그룹 참여", description = "참여한 그룹의 상세를 돌려줍니다.")
  @PostMapping("/members")
  public ResponseEntity<MeetingGroupResponse> join(
      @Parameter(hidden = true) @LoginUser Long userId,
      @Valid @RequestBody JoinGroupRequest request) {
    MeetingGroupResponse response = groupMemberService.join(userId, request.normalizedCode());
    return ResponseEntity.created(URI.create("/api/v1/meeting-groups/" + response.id()))
        .body(response);
  }

  @Operation(summary = "그룹 나가기", description = "방장은 나갈 수 없습니다.")
  @DeleteMapping("/{groupId}/members/me")
  public ResponseEntity<Void> leave(
      @Parameter(hidden = true) @LoginUser Long userId, @PathVariable Long groupId) {
    groupMemberService.leave(userId, groupId);
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "멤버 목록", description = "참여 순 커서 페이지네이션")
  @GetMapping("/{groupId}/members")
  public CursorResponse<GroupMemberSummary> listMembers(
      @Parameter(hidden = true) @LoginUser Long userId,
      @PathVariable Long groupId,
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
    return groupMemberService.listMembers(userId, groupId, cursor, size);
  }
}
