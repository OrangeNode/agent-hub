package com.agenthub.system.auth.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.jwt.JwtService;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.auth.command.LoginCommand;
import com.agenthub.system.auth.controller.request.LoginRequest;
import com.agenthub.system.auth.controller.response.LoginResponse;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.user.dto.UserProfile;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /**
     * 用户登录接口，认证成功后签发 JWT 并返回用户画像。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(new LoginCommand(request.username(), request.password()));
        UserProfile profile = result.profile();
        String token = jwtService.createToken(new LoginUser(
                profile.userId(),
                profile.username(),
                profile.roles()
        ));

        return ApiResponse.success(new LoginResponse(token, profile));
    }

    /**
     * 查询当前登录用户信息。
     */
    @GetMapping("/me")
    public ApiResponse<UserProfile> me(Authentication authentication) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(authService.currentUser(loginUser.username()));
    }
}
