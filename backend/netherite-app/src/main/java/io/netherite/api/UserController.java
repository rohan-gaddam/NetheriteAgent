package io.netherite.api;

import io.netherite.api.model.UserResponse;
import io.netherite.domain.entity.UserEntity;
import io.netherite.domain.repository.OrganizationRepository;
import io.netherite.domain.repository.UserRepository;
import io.netherite.security.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    @GetMapping
    public ResponseEntity<List<UserResponse>> listUsers() {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        List<UserResponse> users = userRepository.findAllByOrganizationId(orgUuid).stream()
                .map(this::toUserResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(users);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUser(@PathVariable("userId") String userId) {
        String orgId = TenantContextHolder.getRequiredOrganizationId();
        UUID orgUuid = UUID.fromString(orgId);

        try {
            UUID userUuid = UUID.fromString(userId);
            return userRepository.findByIdAndOrganizationId(userUuid, orgUuid)
                    .map(user -> ResponseEntity.ok((Object) toUserResponse(user)))
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                            "error", "USER_NOT_FOUND",
                            "message", "User not found")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "USER_NOT_FOUND",
                    "message", "User not found"));
        }
    }

    private UserResponse toUserResponse(UserEntity user) {
        boolean designated = organizationRepository.findById(user.getOrganizationId())
                .map(org -> user.getId().equals(org.getAuthorizedPersonUserId()))
                .orElse(false);
        return UserResponse.builder()
                .id(user.getId().toString())
                .organizationId(user.getOrganizationId().toString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .authorizedPerson(designated)
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
