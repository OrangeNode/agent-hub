package com.agenthub.system.role.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.entity.SysMenu;
import com.agenthub.system.menu.mapper.MenuMapper;
import com.agenthub.system.role.command.CreateRoleCommand;
import com.agenthub.system.role.command.UpdateRoleCommand;
import com.agenthub.system.role.dto.RoleItem;
import com.agenthub.system.role.entity.SysRole;
import com.agenthub.system.role.entity.SysRoleMenu;
import com.agenthub.system.role.mapper.RoleMapper;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final MenuMapper menuMapper;

    public RoleServiceImpl(RoleMapper roleMapper, UserMapper userMapper, MenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
    }

    /**
     * 查询所有角色，并附带当前绑定的菜单 ID。
     */
    @Override
    public List<RoleItem> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)).stream()
                .map(this::toRoleItem)
                .toList();
    }

    /**
     * 查询指定编码下仍启用的角色，用于登录画像和授权校验。
     */
    @Override
    public List<RoleItem> findByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectEnabledByCodes(codes).stream()
                .map(this::toRoleItem)
                .toList();
    }

    /**
     * 创建角色并保存角色菜单关系。
     */
    @Override
    @Transactional
    public RoleItem create(CreateRoleCommand command) {
        boolean existed = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, command.code())) > 0;
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码已存在");
        }
        validateMenuIds(command.menuIds());

        SysRole role = new SysRole();
        role.setCode(command.code());
        role.setName(command.name());
        role.setEnabled(command.enabled());
        roleMapper.insert(role);

        replaceRoleMenus(role.getId(), command.menuIds());
        return requireRoleItem(role.getId());
    }

    /**
     * 更新角色基础信息并整体替换菜单授权关系。
     */
    @Override
    @Transactional
    public RoleItem update(Long roleId, UpdateRoleCommand command) {
        SysRole existed = requireRole(roleId);
        validateMenuIds(command.menuIds());

        SysRole updated = new SysRole();
        updated.setId(existed.getId());
        updated.setCode(existed.getCode());
        updated.setName(command.name());
        updated.setEnabled(command.enabled());
        roleMapper.updateById(updated);

        replaceRoleMenus(roleId, command.menuIds());
        return requireRoleItem(roleId);
    }

    /**
     * 删除未被用户占用的角色，同时清理角色菜单关系。
     */
    @Override
    @Transactional
    public void delete(Long roleId) {
        requireRole(roleId);
        if (userMapper.countUsersByRoleId(roleId) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前角色已分配给用户，不能直接删除");
        }
        roleMapper.deleteRoleMenusByRoleId(roleId);
        roleMapper.deleteById((java.io.Serializable) roleId);
    }

    /**
     * 查询角色实体，不存在时统一抛出业务异常。
     */
    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private RoleItem requireRoleItem(Long roleId) {
        return toRoleItem(requireRole(roleId));
    }

    /**
     * 校验授权菜单 ID 是否全部存在。
     */
    private void validateMenuIds(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        List<Long> distinctMenuIds = menuIds.stream().distinct().toList();
        Set<Long> existingMenuIds = menuMapper.selectByIds(distinctMenuIds).stream()
                .map(SysMenu::getId)
                .collect(Collectors.toSet());
        List<Long> missingMenuIds = distinctMenuIds.stream()
                .filter(menuId -> !existingMenuIds.contains(menuId))
                .toList();
        if (!missingMenuIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单不存在: " + missingMenuIds);
        }
    }

    /**
     * 以整体替换方式维护角色菜单关系，重复菜单 ID 会在写入前去重。
     */
    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        roleMapper.deleteRoleMenusByRoleId(roleId);
        if (menuIds == null) {
            return;
        }
        for (Long menuId : menuIds.stream().distinct().toList()) {
            SysRoleMenu relation = new SysRoleMenu();
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            roleMapper.insertRoleMenu(relation);
        }
    }

    private RoleItem toRoleItem(SysRole role) {
        return new RoleItem(
                role.getId(),
                role.getCode(),
                role.getName(),
                Boolean.TRUE.equals(role.getEnabled()),
                roleMapper.selectMenuIdsByRoleId(role.getId())
        );
    }
}
