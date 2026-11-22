package com.freesia.system.iam.internal.account;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.freesia.system.iam.internal", annotationClass = Mapper.class)
class IamConfiguration {}
