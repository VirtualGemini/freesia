package com.freesia.admin.audit.internal.login;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.freesia.admin.audit.internal", annotationClass = Mapper.class)
class AuditConfiguration {}
