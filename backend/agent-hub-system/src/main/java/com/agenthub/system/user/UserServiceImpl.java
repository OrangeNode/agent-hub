package com.agenthub.system.user;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.role.persistence.RoleMapper;
import com.agenthub.system.role.persistence.SysRole;
import com.agenthub.system.user.persistence.SysUser;
import com.agenthub.system.user.persistence.SysUserRole;
import com.agenthub.system.user.persistence.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public List<UserProfile> list() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId)).stream()
                .map(this::toProfile)
                .toList();
    }

    @Override
    public Optional<UserAccount> findAccountByUsername(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        return Optional.ofNullable(user).map(this::toAccount);
    }

    @Override
    public Optional<UserAccount> findAccountById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(this::toAccount);
    }

    @Override
    @Transactional
    public UserProfile create(CreateUserCommand command) {
        boolean existed = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, command.username())) > 0;
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        List<SysRole> roles = loadRoles(command.roles());

        SysUser user = new SysUser();
        user.setUsername(command.username());
        user.setDisplayName(command.displayName());
        user.setPassword(passwordEncoder.encode(command.password()));
        user.setEnabled(command.enabled());
        userMapper.insert(user);

        replaceUserRoles(user.getId(), roles);
        return toProfile(requireUser(user.getId()));
    }

    @Override
    @Transactional
    public UserProfile update(Long userId, UpdateUserCommand command) {
        SysUser existed = requireUser(userId);
        List<SysRole> roles = loadRoles(command.roles());

        SysUser updated = new SysUser();
        updated.setId(existed.getId());
        updated.setUsername(existed.getUsername());
        updated.setDisplayName(command.displayName());
        updated.setPassword(existed.getPassword());
        updated.setEnabled(command.enabled());
        userMapper.updateById(updated);

        replaceUserRoles(userId, roles);
        return toProfile(requireUser(userId));
    }

    @Override
    @Transactional
    public UserProfile updateStatus(Long userId, boolean enabled) {
        requireUser(userId);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getEnabled, enabled));
        return toProfile(requireUser(userId));
    }

    @Override
    @Transactional
    public void delete(Long userId) {
        requireUser(userId);
        userMapper.deleteUserRolesByUserId(userId);
        userMapper.deleteById(userId);
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private List<SysRole> loadRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = roleMapper.selectByCodes(roleCodes);
        Set<String> existedCodes = roles.stream().map(SysRole::getCode).collect(Collectors.toSet());
        List<String> missingCodes = roleCodes.stream()
                .filter(code -> !existedCodes.contains(code))
                .distinct()
                .toList();
        if (!missingCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在: " + String.join(", ", missingCodes));
        }

        List<String> disabledCodes = roles.stream()
                .filter(role -> !Boolean.TRUE.equals(role.getEnabled()))
                .map(SysRole::getCode)
                .toList();
        if (!disabledCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "禁用角色不能绑定用户: " + String.join(", ", disabledCodes));
        }
        return roles;
    }

    private void replaceUserRoles(Long userId, List<SysRole> roles) {
        userMapper.deleteUserRolesByUserId(userId);
        for (SysRole role : roles) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(role.getId());
            userMapper.insertUserRole(relation);
        }
    }

    private UserAccount toAccount(SysUser user) {
        return new UserAccount(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getPassword(),
                Boolean.TRUE.equals(user.getEnabled()),
                new ArrayList<>(userMapper.selectRoleCodesByUserId(user.getId()))
        );
    }

    private UserProfile toProfile(SysUser user) {
        UserAccount account = toAccount(user);
        return new UserProfile(
                account.userId(),
                account.username(),
                account.displayName(),
                account.enabled(),
                account.roles(),
                List.of()
        );
    }
}
