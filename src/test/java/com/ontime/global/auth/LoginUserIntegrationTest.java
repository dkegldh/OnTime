package com.ontime.global.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ontime.TestcontainersConfiguration;
import com.ontime.user.domain.User;
import com.ontime.user.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LoginUserIntegrationTest.LoginUserTestController.class})
class LoginUserIntegrationTest {

  private static final String TEST_PATH = "/api/v1/test/login-user";

  @RestController
  static class LoginUserTestController {
    @GetMapping(TEST_PATH)
    Map<String, Long> me(@LoginUser Long userId) {
      return Map.of("userId", userId);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;

  @AfterEach
  void tearDown() {
    userRepository.deleteAllInBatch();
  }

  @Test
  @DisplayName("존재하는 사용자 id를 X-User-Id로 보내면 @LoginUser에 그 id가 들어온다")
  void loginUser_existingUserHeader_injectsUserId() throws Exception {
    // given
    Long userId = userRepository.save(User.create("지각대장")).getId();

    // when
    ResultActions result =
        mockMvc.perform(
            get(TEST_PATH).header(LoginUserArgumentResolver.USER_ID_HEADER, userId.toString()));

    // then
    result.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));
  }

  @Test
  @DisplayName("X-User-Id 헤더 없이 호출하면 401 UNAUTHORIZED 응답을 받는다")
  void loginUser_missingHeader_respondsUnauthorized() throws Exception {
    // when
    ResultActions result = mockMvc.perform(get(TEST_PATH));

    // then
    result
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value("로그인이 필요해요."));
  }

  @Test
  @DisplayName("존재하지 않는 사용자 id로 호출하면 401 UNAUTHORIZED 응답을 받는다")
  void loginUser_nonExistingUser_respondsUnauthorized() throws Exception {
    // when
    ResultActions result =
        mockMvc.perform(get(TEST_PATH).header(LoginUserArgumentResolver.USER_ID_HEADER, "999999"));

    // then
    result
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }
}
