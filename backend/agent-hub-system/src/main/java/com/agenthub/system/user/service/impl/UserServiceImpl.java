package com.agenthub.system.user.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.role.entity.SysRole;
import com.agenthub.system.role.mapper.RoleMapper;
import com.agenthub.system.user.command.CreateUserCommand;
import com.agenthub.system.user.command.UpdateUserCommand;
import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.entity.SysUser;
import com.agenthub.system.user.entity.SysUserRole;
import com.agenthub.system.user.mapper.UserMapper;
import com.agenthub.system.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 按查询条件返回用户列表，并把用户角色编码填充到展示 DTO。
     */
    @Override
    public List<UserProfile> list(UserQuery query) {
        return userMapper.selectByQuery(query).stream()
                .map(this::toProfile)
                .toList();
    }

    /**
     * 查询登录认证所需的用户账号信息。
     */
    @Override
    public Optional<UserAccount> findAccountByUsername(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        return Optional.ofNullable(user).map(this::toAccount);
    }

    /**
     * 查询指定用户的账号信息，供 JWT 过滤器校验登录态使用。
     */
    @Override
    public Optional<UserAccount> findAccountById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(this::toAccount);
    }

    /**
     * 创建用户并在同一事务内写入用户角色关系。
     */
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

    /**
     * 更新用户资料并整体替换角色关系。
     */
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

    /**
     * 更新用户启用状态，并返回更新后的用户画像。
     */
    @Override
    @Transactional
    public UserProfile updateStatus(Long userId, boolean enabled) {
        requireUser(userId);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getEnabled, enabled));
        return toProfile(requireUser(userId));
    }

    /**
     * 删除用户前先清理角色关系，避免关联表残留无效数据。
     */
    @Override
    @Transactional
    public void delete(Long userId) {
        requireUser(userId);
        userMapper.deleteUserRolesByUserId(userId);
        userMapper.deleteById((java.io.Serializable) userId);
    }

    /**
     * 查询用户实体，不存在时统一抛出业务异常。
     */
    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 加载并校验待分配角色，拒绝不存在或已禁用的角色编码。
     */
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色已被禁用，不能分配给用户: " + String.join(", ", disabledCodes));
        }
        return roles;
    }

    /**
     * 以整体替换方式维护用户角色关系，避免增量差异计算遗漏删除项。
     */
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
