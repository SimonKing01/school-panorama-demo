package com.school.ontology;

import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本体数据底座独立启动类。
 *
 * <p>边界：本服务只写本体库；对原业务库只有只读连接（运维期使用），
 * 原业务服务与本服务之间只有异步事件，不存在同步依赖。</p>
 */
@SpringBootApplication
@MapperScan("com.school.ontology.repository")
@EnableScheduling
public class OntologyApplication {

    /**
     * 注册中心作为 Bean 暴露：加载器已在构造期完成 YAML 强校验，
     * 这里只是把结果交给 Spring 容器，保证任何使用者拿到的都是已校验定义。
     */
    @Bean
    public ModelRegistry modelRegistry(ModelDefinitionLoader loader) {
        return loader.registry();
    }

    public static void main(String[] args) {
        SpringApplication.run(OntologyApplication.class, args);
    }
}
