package com.mei.zhgy.service;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceAdminAccessTest {
    @Test
    void onlyDatabaseAdminsCanAccessDiagnostics() {
        UserService users = mock(UserService.class);
        when(users.getUserById(1)).thenReturn(User.builder().id(1).role("user").build());
        when(users.getUserById(2)).thenReturn(User.builder().id(2).role("admin").build());
        var access = new WorkspaceAdminAccess(users);
        try {
            BaseContext.removeCurrentId();
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, access::requireAdmin).getStatus());
            BaseContext.setCurrentId(1L);
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, access::requireAdmin).getStatus());
            BaseContext.setCurrentId(2L);
            assertDoesNotThrow(access::requireAdmin);
        } finally { BaseContext.removeCurrentId(); }
    }
}
