package com.agenthub.system.role;

import java.util.List;

public interface RoleService {

    List<RoleItem> list();

    List<RoleItem> findByCodes(List<String> codes);

    RoleItem create(CreateRoleCommand command);

    RoleItem update(Long roleId, UpdateRoleCommand command);

    void delete(Long roleId);
}
