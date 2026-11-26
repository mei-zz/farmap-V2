package com.mei.zhgy.service;

import com.mei.zhgy.context.BaseContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceAdminAccess {
    private final UserService users;

    public WorkspaceAdminAccess(UserService users) {
        this.users = users;
    }

    public void requireAdmin() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录。");
        var user = users.getUserById(userId.intValue());
        if (user == null || !"admin".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可查看。");
        }
    }
}
