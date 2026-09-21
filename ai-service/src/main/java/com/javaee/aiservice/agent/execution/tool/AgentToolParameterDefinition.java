package com.javaee.aiservice.agent.execution.tool;

/**
 * 【简历：Agent 工具参数 Schema 定义】
 * 支持参数类型推断、必填校验、缺省值、枚举约束(allowedValues)、
 * 数值范围(min/max)和正则校验(pattern)。
 */

import java.util.List;

/**
 * Machine-readable schema for one Agent tool parameter.
 */
public class AgentToolParameterDefinition {

    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private final Object defaultValue;
    private final List<Object> allowedValues;
    private final Number minValue;
    private final Number maxValue;
    private final String pattern;

    public AgentToolParameterDefinition(String name, String type, String description, boolean required, Object defaultValue) {
        this(name, type, description, required, defaultValue, null, null, null, null);
    }

    public AgentToolParameterDefinition(String name, String type, String description, boolean required,
                                        Object defaultValue, List<Object> allowedValues,
                                        Number minValue, Number maxValue, String pattern) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
        this.defaultValue = defaultValue;
        this.allowedValues = allowedValues;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.pattern = pattern;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public List<Object> getAllowedValues() {
        return allowedValues;
    }

    public Number getMinValue() {
        return minValue;
    }

    public Number getMaxValue() {
        return maxValue;
    }

    public String getPattern() {
        return pattern;
    }
}
