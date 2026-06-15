package com.agenthub.skill.mapper;

import com.agenthub.skill.entity.SkillPackage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillPackageMapper extends BaseMapper<SkillPackage> {

    @Select("select * from skill_package where name = #{name} limit 1")
    SkillPackage selectByName(String name);

    @Select("select * from skill_package where skill_key = #{skillKey} limit 1")
    SkillPackage selectBySkillKey(String skillKey);

    @Select("select * from skill_package order by updated_at desc, id desc")
    List<SkillPackage> selectOrderedList();
}
