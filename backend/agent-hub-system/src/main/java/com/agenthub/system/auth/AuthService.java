package com.agenthub.system.auth;

import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.user.UserProfile;
import java.util.List;

public interface AuthService {

    LoginResult login(LoginCommand command);

    UserProfile currentUser(String username);

    List<UserProfile> listUsers();

    List<RoleItem> listRoles();

    List<PermissionItem> listPermissions();

    List<MenuItem> listMenus();
}
