package com.freesia.framework.data;

import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.spring.boot.SqlSessionFactoryBeanCustomizer;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * One DataSource and one Spring transaction manager for JdbcClient reads and MyBatis-Flex writes.
 * Flex would otherwise wrap the pool itself and manage transactions on its own connection, so a
 * JdbcClient query could not see, or roll back with, a Flex write made in the same transaction.
 * {@link FlexTransactionExclusion} removes Flex's transaction manager for the same reason.
 */
@AutoConfiguration(
        before = DataSourceAutoConfiguration.class,
        beforeName = "com.mybatisflex.spring.boot.v4.MybatisFlexAutoConfiguration")
@ConditionalOnMissingBean(DataSource.class)
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource connectionPool(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    FlexDataSource dataSource(HikariDataSource connectionPool) {
        return new FlexDataSource("freesia", connectionPool);
    }

    @Bean
    SqlSessionFactoryBeanCustomizer springManagedTransactions() {
        return factory -> factory.setTransactionFactory(new SpringManagedTransactionFactory());
    }
}
