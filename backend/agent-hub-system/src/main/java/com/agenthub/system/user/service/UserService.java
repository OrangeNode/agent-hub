package com.agenthub.system.user.service;

import com.agenthub.system.user.command.CreateUserCommand;
import com.agenthub.system.user.command.UpdateUserCommand;
import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import java.util.List;
import java.util.Optional;

public interface UserService {

    List<UserProfile> list(UserQuery query);

    Optional<UserAccount> findAccountByUsername(String username);

    Optional<UserAccount> findAccountById(Long userId);

    UserProfile create(CreateUserCommand command);

    UserProfile update(Long userId, UpdateUserCommand command);

    UserProfile updateStatus(Long userId, boolean enabled);

    void delete(Long userId);
}
