package com.javaee.user.vo;

import lombok.Data;

/** Safe user fields exposed by the registered-user directory. */
@Data
public class UserDirectoryVO {
    private Long id;
    private String username;
    private String nickname;
}
