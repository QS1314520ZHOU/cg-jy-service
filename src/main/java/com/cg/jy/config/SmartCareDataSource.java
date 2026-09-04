package com.cg.jy.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * SmartCare 数据源 —— Spring 管理的单例 Bean。
 *
 * 【重要】MongoClient / MongoTemplate 是本类的 **私有字段**，
 * Spring 不会把它们注册成容器 Bean，因此不会触发 MongoAutoConfiguration 的
 * @ConditionalOnMissingBean 保护，主数据源（DataCenter）不受影响。
 */
@Component
public class SmartCareDataSource implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SmartCareDataSource.class);

    private final MongoClient client;
    private final MongoTemplate template;
    private final String databaseName;

    public SmartCareDataSource(
            @Value("${smartcare.mongodb.uri:mongodb://localhost:27017/SmartCare}") String uri) {
        ConnectionString cs = new ConnectionString(uri);
        this.databaseName = cs.getDatabase() != null ? cs.getDatabase() : "SmartCare";
        this.client = MongoClients.create(
                MongoClientSettings.builder().applyConnectionString(cs).build());
        this.template = new MongoTemplate(client, databaseName);
        logger.info("SmartCare 数据源已初始化, database={}", databaseName);
    }

    public MongoTemplate template() {
        return template;
    }

    public String databaseName() {
        return databaseName;
    }

    @Override
    public void close() {
        try {
            if (client != null) client.close();
        } catch (Exception e) {
            logger.warn("关闭 SmartCare MongoClient 失败", e);
        }
    }
}
