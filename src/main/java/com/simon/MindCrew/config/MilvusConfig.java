package com.simon.MindCrew.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 向量数据库配置
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private Integer port;

    /** 建连及单次 RPC 必须有上限，避免向量库异常时阻塞 Spring 启动或业务线程。 */
    @Value("${milvus.connect-timeout-seconds:5}")
    private long connectTimeoutSeconds;

    @Value("${milvus.rpc-deadline-seconds:10}")
    private long rpcDeadlineSeconds;

    @Bean
    @Lazy
    public MilvusServiceClient milvusServiceClient() {
        log.info("初始化 Milvus 客户端: {}:{}", host, port);
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(Math.max(1, connectTimeoutSeconds), TimeUnit.SECONDS)
                .withRpcDeadline(Math.max(1, rpcDeadlineSeconds), TimeUnit.SECONDS)
                .build();
        return new MilvusServiceClient(connectParam);
    }
}
