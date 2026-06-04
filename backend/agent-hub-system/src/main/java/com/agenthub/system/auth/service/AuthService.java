package com.agenthub.system.auth.service;

import com.agenthub.system.auth.command.LoginCommand;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.user.dto.UserProfile;
import java.util.List;

public interface AuthService {

    /**
     * 校验账号密码并生成当前登录用户画像。
     */
    LoginResult login(LoginCommand command);

    /**
     * 根据用户名加载当前登录用户的角色和权限信息。
     */
    UserProfile currentUser(String username);

    /**
     * 汇总系统菜单中声明的权限标识，供权限配置界面选择。
     */
    List<PermissionItem> listPermissions();
}
