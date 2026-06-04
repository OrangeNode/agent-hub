package com.agenthub.chat.message.mapper;

import com.agenthub.chat.message.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息表数据访问接口，基础 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
