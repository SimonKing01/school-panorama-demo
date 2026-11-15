package com.school.ontology.repository;

import com.school.ontology.core.projection.TxExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 本体库单事务执行器：底座侧「对象 + 关系 + 对象版本 + 变更历史 + 影响 +
 * 来源引用 + 平台版本 + processed_event」全部在同一事务内完成，提交后才返回 ACK。
 */
@Component
public class JdbcTxExecutor implements TxExecutor {

    private final TransactionTemplate template;

    public JdbcTxExecutor(@Qualifier("ontologyTransactionManager") PlatformTransactionManager manager) {
        this.template = new TransactionTemplate(manager);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
