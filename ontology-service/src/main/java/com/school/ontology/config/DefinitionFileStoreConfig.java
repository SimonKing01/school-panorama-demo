package com.school.ontology.config;

import com.school.ontology.core.model.DefinitionFileStore;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 两份 overlay 的 Bean 注册处（C 档对象类型在线编辑）。
 *
 * <p>同一个 {@link DefinitionFileStore} 类注册两个实例，必须按 qualifier 注入：
 * {@code relationDefinitionFileStore} 对应 relation-types.yaml，
 * {@code objectDefinitionFileStore} 对应 object-types.yaml。两者锁、备份与回退留痕互相独立，
 * 「恢复对象基线」绝不会碰到关系 overlay。</p>
 */
@Configuration
public class DefinitionFileStoreConfig {

    @Bean("relationDefinitionFileStore")
    public DefinitionFileStore relationDefinitionFileStore(
            @Value("${ontology.definition-dir:/app/ontology-data}") String directory,
            ObjectProvider<OntologyRepository> repositoryProvider) {
        return new DefinitionFileStore(Paths.get(directory), repositoryProvider.getIfAvailable(),
                DefinitionFileStore.OVERLAY_FILE);
    }

    @Bean("objectDefinitionFileStore")
    public DefinitionFileStore objectDefinitionFileStore(
            @Value("${ontology.definition-dir:/app/ontology-data}") String directory,
            ObjectProvider<OntologyRepository> repositoryProvider) {
        return new DefinitionFileStore(Paths.get(directory), repositoryProvider.getIfAvailable(),
                DefinitionFileStore.OBJECT_OVERLAY_FILE);
    }
}
