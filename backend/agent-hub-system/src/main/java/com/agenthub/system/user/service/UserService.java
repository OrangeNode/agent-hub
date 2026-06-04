package com.agenthub.system.user.service;

import com.agenthub.system.user.command.CreateUserCommand;
import com.agenthub.system.user.command.UpdateUserCommand;
import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import java.util.List;
import java.util.Optional;

public interface UserService {

    /**
     * 按组合条件查询用户列表，并附带用户角色编码。
     */
    List<UserProfile> list(UserQuery query);

    /**
     * 根据用户名查找登录所需的账号信息。
     */
    Optional<UserAccount> findAccountByUsername(String username);

    /**
     * 根据用户 ID 查找登录所需的账号信息。
     */
    Optional<UserAccount> findAccountById(Long userId);

    /**
     * 创建用户并同步分配角色。
     */
    UserProfile create(CreateUserCommand command);

    /**
     * 更新用户基础信息和角色关系。
     */
    UserProfile update(Long userId, UpdateUserCommand command);

    /**
     * 启用或禁用指定用户。
     */
    UserProfile updateStatus(Long userId, boolean enabled);

    /**
     * 删除用户及其角色关联关系。
     */
    void delete(Long userId);
}
