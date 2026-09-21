package com.javaee.documentservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaee.documentservice.entity.ToolboxJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 工具箱任务 Mapper。状态变更必须带当前状态条件，保证 RabbitMQ 重复消息幂等。
 */
@Mapper
public interface ToolboxJobMapper extends BaseMapper<ToolboxJob> {

    ToolboxJob selectOwned(@Param("jobId") String jobId,
                           @Param("userId") Long userId,
                           @Param("organizationId") String organizationId);

    ToolboxJob selectByUser(@Param("jobId") String jobId,
                            @Param("userId") Long userId);

    List<ToolboxJob> listOwned(@Param("userId") Long userId,
                               @Param("organizationId") String organizationId,
                               @Param("limit") int limit);

    List<ToolboxJob> listByUser(@Param("userId") Long userId,
                                @Param("limit") int limit);

    int claimPending(@Param("jobId") String jobId,
                     @Param("staleBefore") Instant staleBefore);

    int markSuccess(@Param("jobId") String jobId,
                    @Param("resultObjectKey") String resultObjectKey,
                    @Param("fileName") String fileName,
                    @Param("contentType") String contentType,
                    @Param("message") String message);

    int markFailure(@Param("jobId") String jobId,
                    @Param("message") String message);

    int updateProgress(@Param("jobId") String jobId,
                       @Param("progress") int progress,
                       @Param("message") String message);

    int retryFailed(@Param("jobId") String jobId,
                    @Param("userId") Long userId,
                    @Param("organizationId") String organizationId);

    int resetStaleProcessing(@Param("organizationId") String organizationId,
                             @Param("staleBefore") Instant staleBefore);

    int resetAllStaleProcessing(@Param("staleBefore") Instant staleBefore);

    int markExpired(@Param("jobId") String jobId,
                    @Param("now") Instant now);

    List<ToolboxJob> selectDispatchable(@Param("before") Instant before,
                                        @Param("limit") int limit);

    int markDispatched(@Param("jobId") String jobId);

    List<ToolboxJob> selectExpired(@Param("now") Instant now,
                                   @Param("limit") int limit);
}
