package com.javaee.aiservice.skills;

/**
 * 【简历：AI Skills 接口】
 * AI Skills 能力的统一接口，定义 getName、getDescription、execute 方法。
 * 所有具体 Skill（文件上传、下载、PPT 生成等）实现此接口。
 */

public interface Skill {
    String getName();
    String getDescription();
    Object execute(Object... parameters);
}
