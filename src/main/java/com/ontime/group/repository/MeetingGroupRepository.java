package com.ontime.group.repository;

import com.ontime.group.domain.MeetingGroup;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingGroupRepository extends JpaRepository<MeetingGroup, Long> {

  /** 삭제(soft delete)되지 않은 그룹만 찾는다. */
  Optional<MeetingGroup> findByIdAndDeletedAtIsNull(Long id);
}
