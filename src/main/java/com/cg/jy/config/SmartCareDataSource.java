package com.cg.jy.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 呼吸机辅助呼吸视图（及后续新表单菜单）使用的 SmartCare 数据源。
 *
 * 【重要】这里刻意**不**把 MongoClient / MongoTemplate 注册成 Spring bean，而是用普通对象持有。
 *
 * 原因：Spring Boot 的自动配置上有条件注解
 *   - MongoAutoConfiguration       : @ConditionalOnMissingBean(MongoClient.class)
 *   - MongoDataAutoConfiguration   : @ConditionalOnMissingBean(MongoTemplate.class)
 * 一旦容器中出现自定义的 MongoClient 或 MongoTemplate，自动配置就会整体跳过，
 * 主数据源（spring.data.mongodb.uri -> DataCenter）的 mongoTemplate 不再创建，
 * 检验页 LabController 的 @Autowired MongoTemplate 就会注入到 SmartCare 上，导致查错库。
 *
 * 因此本类只做连接封装，由使用方（VentController）自己创建和关闭，
 * 保证容器里始终只有自动配置的那一个 MongoTemplate。
 */
public class SmartCareDataSource implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SmartCareDataSource.class);

    private final MongoClient client;
    private final MongoTemplate template;
    private final String databaseName;

    public SmartCareDataSource(String uri) {
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
