package com.agenthub.system.auth.service;

import com.agenthub.system.auth.command.LoginCommand;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.user.dto.UserProfile;
import java.util.List;

public interface AuthService {

    LoginResult login(LoginCommand command);

    UserProfile currentUser(String username);

    List<PermissionItem> listPermissions();
}
