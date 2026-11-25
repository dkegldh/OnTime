package com.ontime.user.service;

import com.ontime.user.domain.User;
import com.ontime.user.dto.UserCreateRequest;
import com.ontime.user.dto.UserResponse;
import com.ontime.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;

  @Transactional
  public UserResponse create(UserCreateRequest request) {
    User user = userRepository.save(User.create(request.nickname()));
    return UserResponse.from(user);
  }

  public List<UserResponse> findAll() {
    return userRepository.findAll(Sort.by("id")).stream().map(UserResponse::from).toList();
  }

  public boolean exists(Long userId) {
    return userRepository.existsById(userId);
  }

  /**
   * 연관관계를 걸 때 쓰는 사용자 참조(프록시)를 돌려준다. 조회 쿼리를 보내지 않는다.
   *
   * <p>존재 여부는 호출하는 쪽이 이미 확인했다고 가정한다. (예: {@code @LoginUser} 가 검증한 id)
   */
  public User getReference(Long userId) {
    return userRepository.getReferenceById(userId);
  }
}
