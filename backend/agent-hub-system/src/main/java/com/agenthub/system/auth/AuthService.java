package com.agenthub.system.auth;

import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.user.UserProfile;
import java.util.List;

public interface AuthService {

    LoginResult login(LoginCommand command);

    UserProfile currentUser(String username);

    List<PermissionItem> listPermissions();
}
