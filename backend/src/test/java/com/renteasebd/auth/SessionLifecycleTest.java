package com.renteasebd.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renteasebd.domain.user.User;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class SessionLifecycleTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    @Transactional
    void revokedSessionInvalidatesPreviouslyIssuedAccessToken() throws Exception {
        User user = user("session-revoke@example.com");
        JsonNode login = login(user);
        String access = login.path("accessToken").asText();
        String refresh = login.path("refreshToken").asText();
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer " + access))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void disabledAccountCannotKeepUsingAccessToken() throws Exception {
        User user = user("session-disabled@example.com");
        String access = login(user).path("accessToken").asText();
        user.setActive(false);
        users.saveAndFlush(user);
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer " + access))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void refreshRotatesAndRejectsOldToken() throws Exception {
        User user = user("session-rotate@example.com");
        String first = login(user).path("refreshToken").asText();
        String response = mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", first))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String next = mapper.readTree(response).path("data").path("refreshToken").asText();
        assertThat(next).isNotEqualTo(first);
        mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", first))))
            .andExpect(status().is4xxClientError());
    }

    private User user(String email) {
        User user = new User();
        user.setFullName("Session Owner");
        user.setEmail(email);
        user.setPasswordHash(passwords.encode("Password@123"));
        user.setRole(UserRole.OWNER);
        user.setPreferredLanguage("en");
        user.setActive(true);
        return users.save(user);
    }

    private JsonNode login(User user) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", user.getEmail(), "password", "Password@123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }
}
