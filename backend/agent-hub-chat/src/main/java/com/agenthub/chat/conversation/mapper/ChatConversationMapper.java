package com.agenthub.chat.conversation.mapper;

import com.agenthub.chat.conversation.entity.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天会话表数据访问接口，基础 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
