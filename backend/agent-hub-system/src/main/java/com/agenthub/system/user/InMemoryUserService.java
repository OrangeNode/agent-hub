package com.agenthub.system.user;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.support.InMemorySystemStore;
import java.util.List;
import java.util.Optional;

public class InMemoryUserService implements UserService {

    private final InMemorySystemStore store;

    public InMemoryUserService() {
        this(new InMemorySystemStore());
    }

    public InMemoryUserService(InMemorySystemStore store) {
        this.store = store;
    }

    @Override
    public List<UserProfile> list() {
        return store.users().values().stream()
                .map(this::toProfile)
                .toList();
    }

    @Override
    public Optional<UserAccount> findAccountByUsername(String username) {
        return store.users().values().stream()
                .filter(user -> user.username().equals(username))
                .findFirst();
    }

    @Override
    public Optional<UserAccount> findAccountById(Long userId) {
        return Optional.ofNullable(store.users().get(userId));
    }

    @Override
    public UserProfile create(CreateUserCommand command) {
        boolean existed = store.users().values().stream()
                .anyMatch(user -> user.username().equals(command.username()));
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        long userId = store.nextUserId();
        UserAccount userAccount = new UserAccount(
                userId,
                command.username(),
                command.displayName(),
                command.password(),
                command.enabled(),
                List.copyOf(command.roles())
        );
        store.users().put(userId, userAccount);
        return toProfile(userAccount);
    }

    @Override
    public UserProfile update(Long userId, UpdateUserCommand command) {
        UserAccount existed = requireUser(userId);
        UserAccount updated = new UserAccount(
                existed.userId(),
                existed.username(),
                command.displayName(),
                existed.password(),
                command.enabled(),
                List.copyOf(command.roles())
        );
        store.users().put(userId, updated);
        return toProfile(updated);
    }

    @Override
    public UserProfile updateStatus(Long userId, boolean enabled) {
        UserAccount existed = requireUser(userId);
        UserAccount updated = new UserAccount(
                existed.userId(),
                existed.username(),
                existed.displayName(),
                existed.password(),
                enabled,
                existed.roles()
        );
        store.users().put(userId, updated);
        return toProfile(updated);
    }

    @Override
    public void delete(Long userId) {
        if (store.users().remove(userId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
    }

    private UserAccount requireUser(Long userId) {
        return findAccountById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private UserProfile toProfile(UserAccount userAccount) {
        return new UserProfile(
                userAccount.userId(),
                userAccount.username(),
                userAccount.displayName(),
                userAccount.enabled(),
                userAccount.roles(),
                List.of()
        );
    }
}
