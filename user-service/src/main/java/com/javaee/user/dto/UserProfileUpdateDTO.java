package com.javaee.user.dto;

import lombok.Data;

@Data
public class UserProfileUpdateDTO {
    private String nickname;
    private String signature;
    private String avatarFileId;
}