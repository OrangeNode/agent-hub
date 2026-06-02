package com.agenthub.system.role.service;

import com.agenthub.system.role.command.CreateRoleCommand;
import com.agenthub.system.role.command.UpdateRoleCommand;
import com.agenthub.system.role.dto.RoleItem;
import java.util.List;

public interface RoleService {

    List<RoleItem> list();

    List<RoleItem> findByCodes(List<String> codes);

    RoleItem create(CreateRoleCommand command);

    RoleItem update(Long roleId, UpdateRoleCommand command);

    void delete(Long roleId);
}
