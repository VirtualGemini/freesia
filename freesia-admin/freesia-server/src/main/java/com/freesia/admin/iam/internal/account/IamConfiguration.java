package com.freesia.admin.iam.internal.account;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.freesia.admin.iam.internal", annotationClass = Mapper.class)
class IamConfiguration {}
