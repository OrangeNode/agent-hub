package com.agenthub.system.role;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.support.InMemorySystemStore;
import java.util.List;

public class InMemoryRoleService implements RoleService {

    private final InMemorySystemStore store;

    public InMemoryRoleService() {
        this(new InMemorySystemStore());
    }

    public InMemoryRoleService(InMemorySystemStore store) {
        this.store = store;
    }

    @Override
    public List<RoleItem> list() {
        return store.roles().values().stream().toList();
    }

    @Override
    public List<RoleItem> findByCodes(List<String> codes) {
        return store.roles().values().stream()
                .filter(role -> role.enabled() && codes.contains(role.code()))
                .toList();
    }

    @Override
    public RoleItem create(CreateRoleCommand command) {
        boolean existed = store.roles().values().stream()
                .anyMatch(role -> role.code().equals(command.code()));
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码已存在");
        }

        long roleId = store.nextRoleId();
        RoleItem roleItem = new RoleItem(
                roleId,
                command.code(),
                command.name(),
                command.enabled(),
                List.copyOf(command.menuIds())
        );
        store.roles().put(roleId, roleItem);
        return roleItem;
    }

    @Override
    public RoleItem update(Long roleId, UpdateRoleCommand command) {
        RoleItem existed = requireRole(roleId);
        RoleItem updated = new RoleItem(
                existed.roleId(),
                existed.code(),
                command.name(),
                command.enabled(),
                List.copyOf(command.menuIds())
        );
        store.roles().put(roleId, updated);
        return updated;
    }

    @Override
    public void delete(Long roleId) {
        if (store.roles().remove(roleId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
    }

    private RoleItem requireRole(Long roleId) {
        RoleItem roleItem = store.roles().get(roleId);
        if (roleItem == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return roleItem;
    }
}
