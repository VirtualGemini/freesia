package com.freesia.system.audit.internal.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.freesia.system.audit.internal", annotationClass = Mapper.class)
class AuditConfiguration {}
