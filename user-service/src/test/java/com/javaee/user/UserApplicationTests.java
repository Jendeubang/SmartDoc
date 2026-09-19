package com.javaee.user;

import com.javaee.user.config.UserProfileSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class UserApplicationTests {

    @MockBean
    private UserProfileSchemaInitializer userProfileSchemaInitializer;

    @Test
    void contextLoads() {
    }

}
