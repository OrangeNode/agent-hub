UPDATE sys_menu
SET name = 'skills'
WHERE name = 'skillLibrary'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu existing
        WHERE existing.name = 'skills'
    );

UPDATE sys_menu
SET title = 'Skill 库',
    path = '/agent/skills',
    component = 'agent/skills/index',
    icon = 'FolderOpenOutlined',
    permission = 'skill:list',
    menu_type = 'MENU',
    visible = TRUE,
    sort = 2,
    updated_by = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'skills'
    OR path = '/agent/skills'
    OR permission IN ('skill:list', 'skill:package:list');

UPDATE sys_menu
SET name = 'skillUpload',
    title = '上传 Skill',
    path = 'skill:upload',
    permission = 'skill:upload',
    menu_type = 'BUTTON',
    visible = FALSE,
    sort = 1,
    parent_id = (
        SELECT menu.id
        FROM sys_menu menu
        WHERE menu.path = '/agent/skills'
        ORDER BY menu.id
        FETCH FIRST 1 ROW ONLY
    ),
    updated_by = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'skillPackageUpload'
    OR permission = 'skill:package:upload';

UPDATE sys_menu
SET name = 'skillDelete',
    title = '删除 Skill',
    path = 'skill:delete',
    permission = 'skill:delete',
    menu_type = 'BUTTON',
    visible = FALSE,
    sort = 2,
    parent_id = (
        SELECT menu.id
        FROM sys_menu menu
        WHERE menu.path = '/agent/skills'
        ORDER BY menu.id
        FETCH FIRST 1 ROW ONLY
    ),
    updated_by = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'skillPackageDelete'
    OR permission = 'skill:package:delete';

DELETE FROM sys_role_menu
WHERE menu_id IN (
    SELECT id
    FROM sys_menu
    WHERE permission IN (
        'skill:package:create',
        'skill:package:update',
        'skill:package:enable',
        'skill:version:activate',
        'skill:version:read'
    )
);

DELETE FROM sys_menu
WHERE permission IN (
    'skill:package:create',
    'skill:package:update',
    'skill:package:enable',
    'skill:version:activate',
    'skill:version:read'
);

INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT parent.id, 'skills', 'Skill 库', '/agent/skills', 'agent/skills/index', 'FolderOpenOutlined', 'skill:list', 'MENU', TRUE, 2, 1, 1
FROM sys_menu parent
WHERE parent.parent_id = 0
    AND parent.name = 'agent'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu child
        WHERE child.parent_id = parent.id
            AND child.path = '/agent/skills'
    );

INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT menu.id, 'skillUpload', '上传 Skill', 'skill:upload', NULL, NULL, 'skill:upload', 'BUTTON', FALSE, 1, 1, 1
FROM sys_menu menu
WHERE menu.path = '/agent/skills'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu button
        WHERE button.parent_id = menu.id
            AND button.permission = 'skill:upload'
    );

INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT menu.id, 'skillDelete', '删除 Skill', 'skill:delete', NULL, NULL, 'skill:delete', 'BUTTON', FALSE, 2, 1, 1
FROM sys_menu menu
WHERE menu.path = '/agent/skills'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu button
        WHERE button.parent_id = menu.id
            AND button.permission = 'skill:delete'
    );

INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT menu.id, 'agentSkillUpdate', '维护 Agent Skill 绑定', 'agent:skill:update', NULL, NULL, 'agent:skill:update', 'BUTTON', FALSE, 3, 1, 1
FROM sys_menu menu
WHERE menu.name = 'agentList'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu button
        WHERE button.parent_id = menu.id
            AND button.permission = 'agent:skill:update'
    );

DELETE FROM sys_role_menu
WHERE menu_id IN (
    SELECT id
    FROM sys_menu
    WHERE permission IN (
        'skill:package:list',
        'skill:package:upload',
        'skill:package:delete'
    )
);

INSERT INTO sys_role_menu (role_id, menu_id, created_by, updated_by)
SELECT sr.id, sm.id, 1, 1
FROM sys_role sr
JOIN sys_menu sm ON sm.permission IN (
    'skill:list',
    'skill:upload',
    'skill:delete',
    'agent:skill:update'
)
WHERE sr.code = 'ADMIN'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_role_menu srm
        WHERE srm.role_id = sr.id
            AND srm.menu_id = sm.id
    );

UPDATE chat_conversation
SET title = COALESCE((
        SELECT SUBSTRING(REPLACE(message.content, '?', '') FROM 1 FOR 15)
        FROM chat_message message
        WHERE message.conversation_id = chat_conversation.id
            AND message.role = 'USER'
            AND LENGTH(TRIM(REPLACE(message.content, '?', ''))) > 0
        ORDER BY
            CASE WHEN message.content LIKE '?%' THEN 1 ELSE 0 END,
            message.created_at,
            message.id
        FETCH FIRST 1 ROW ONLY
    ), '新会话'),
    updated_at = CURRENT_TIMESTAMP
WHERE title LIKE '?%';
