package com.agenthub.system.user.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.user.controller.request.CreateUserRequest;
import com.agenthub.system.user.controller.request.UpdateUserRequest;
import com.agenthub.system.user.command.CreateUserCommand;
import com.agenthub.system.user.command.UpdateUserCommand;
import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/users")
@PreAuthorize("hasAuthority('system:user:list')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查询用户管理列表。
     */
    @GetMapping
    public ApiResponse<List<UserProfile>> listUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String roleCode
    ) {
        return ApiResponse.success(userService.list(new UserQuery(username, displayName, enabled, roleCode)));
    }

    /**
     * 创建系统用户。
     */
    @PostMapping
    public ApiResponse<UserProfile> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(userService.create(new CreateUserCommand(
                request.username(),
                request.displayName(),
                request.password(),
                request.enabled(),
                request.roles()
        )));
    }

    /**
     * 更新系统用户资料和角色。
     */
    @PutMapping("/{userId}")
    public ApiResponse<UserProfile> updateUser(@PathVariable Long userId, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.update(userId, new UpdateUserCommand(
                request.displayName(),
                request.enabled(),
                request.roles()
        )));
    }

    /**
     * 启用或禁用系统用户。
     */
    @PatchMapping("/{userId}/status")
    public ApiResponse<UserProfile> updateUserStatus(@PathVariable Long userId, @RequestParam boolean enabled) {
        return ApiResponse.success(userService.updateStatus(userId, enabled));
    }

    /**
     * 删除系统用户。
     */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        userService.delete(userId);
        return ApiResponse.success();
    }
}
