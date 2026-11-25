package com.ontime.group.dto;

import com.ontime.group.domain.MeetingGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 그룹 이름 수정. 장소, 시간, 벌금 규칙 수정은 Phase 5에서 다룬다. */
public record UpdateMeetingGroupRequest(
    @NotBlank @Size(max = MeetingGroup.NAME_MAX_LENGTH) String name) {}
