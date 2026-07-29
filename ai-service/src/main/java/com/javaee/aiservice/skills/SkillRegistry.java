package com.javaee.aiservice.skills;

/**
 * 【简历：Skills 注册中心】
 * 管理所有 AI Skills 的注册与获取，支持按名称查找。
 */

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.security.BucketPermissionService;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.aiservice.service.MinIOService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new HashMap<>();

    @Autowired
    public SkillRegistry(MinIOService minIOService, ChatService chatService,
                         BucketPermissionService bucketPermissionService,
                         RequestUserContext requestUserContext) {
        registerSkill(new FileUploadSkill(minIOService, bucketPermissionService, requestUserContext));
        registerSkill(new FileDownloadSkill(minIOService, bucketPermissionService, requestUserContext));
        registerSkill(new HtmlPptSkill(chatService));
    }

    public void registerSkill(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    public Skill getSkill(String name) {
        return skills.get(name);
    }

    public Map<String, Skill> getAllSkills() {
        return skills;
    }
}
