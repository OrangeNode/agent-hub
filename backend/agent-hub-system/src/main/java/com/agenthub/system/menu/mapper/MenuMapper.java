package com.agenthub.system.menu.mapper;

import com.agenthub.system.menu.entity.SysMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜单表数据访问接口，基础 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface MenuMapper extends BaseMapper<SysMenu> {
}
