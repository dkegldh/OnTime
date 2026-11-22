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
}
