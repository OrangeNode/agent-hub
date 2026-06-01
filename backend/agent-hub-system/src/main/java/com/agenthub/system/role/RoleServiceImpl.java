package com.agenthub.system.role;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.persistence.MenuMapper;
import com.agenthub.system.menu.persistence.SysMenu;
import com.agenthub.system.role.persistence.RoleMapper;
import com.agenthub.system.role.persistence.SysRole;
import com.agenthub.system.role.persistence.SysRoleMenu;
import com.agenthub.system.user.persistence.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final MenuMapper menuMapper;

    public RoleServiceImpl(RoleMapper roleMapper, UserMapper userMapper, MenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
    }

    @Override
    public List<RoleItem> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)).stream()
                .map(this::toRoleItem)
                .toList();
    }

    @Override
    public List<RoleItem> findByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectEnabledByCodes(codes).stream()
                .map(this::toRoleItem)
                .toList();
    }

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

    @Override
    @Transactional
    public void delete(Long roleId) {
        requireRole(roleId);
        if (userMapper.countUsersByRoleId(roleId) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色已被用户绑定，不能删除");
        }
        roleMapper.deleteRoleMenusByRoleId(roleId);
        roleMapper.deleteById(roleId);
    }

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

    private void validateMenuIds(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        List<Long> distinctMenuIds = menuIds.stream().distinct().toList();
        Set<Long> existingMenuIds = menuMapper.selectBatchIds(distinctMenuIds).stream()
                .map(SysMenu::getId)
                .collect(Collectors.toSet());
        List<Long> missingMenuIds = distinctMenuIds.stream()
                .filter(menuId -> !existingMenuIds.contains(menuId))
                .toList();
        if (!missingMenuIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单不存在: " + missingMenuIds);
        }
    }

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
