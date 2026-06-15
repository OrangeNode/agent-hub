package com.agenthub.skill.mapper;

import com.agenthub.skill.entity.SkillFileManifest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillFileManifestMapper extends BaseMapper<SkillFileManifest> {

    @Delete("delete from skill_file_manifest where skill_id = #{skillId}")
    int deleteBySkillId(Long skillId);

    @Select("select * from skill_file_manifest where skill_id = #{skillId} order by relative_path asc")
    List<SkillFileManifest> selectBySkillId(Long skillId);
}
