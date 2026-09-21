package com.javaee.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RefreshTokenVO {
    private String accessToken;
    private String refreshToken;
}
