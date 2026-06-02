package com.agenthub.system.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.system.role.mapper.RoleMapper;
import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.entity.SysUser;
import com.agenthub.system.user.mapper.UserMapper;
import com.agenthub.system.user.service.impl.UserServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceImplSearchTest {

    @Test
    void shouldFilterUsersWithCombinedConditions() {
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserServiceImpl userService = new UserServiceImpl(userMapper, roleMapper, passwordEncoder);
        UserQuery query = new UserQuery("zhang", "张", true, "USER");

        SysUser user = new SysUser();
        user.setId(2L);
        user.setUsername("zhangsan");
        user.setDisplayName("张三");
        user.setPassword("encoded");
        user.setEnabled(true);

        when(userMapper.selectByQuery(query)).thenReturn(List.of(user));
        when(userMapper.selectRoleCodesByUserId(2L)).thenReturn(List.of("USER"));

        List<UserProfile> result = userService.list(query);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().username()).isEqualTo("zhangsan");
        assertThat(result.getFirst().displayName()).isEqualTo("张三");
        assertThat(result.getFirst().roles()).containsExactly("USER");
        verify(userMapper).selectByQuery(query);
    }
}
