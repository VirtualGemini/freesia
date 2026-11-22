package com.freesia.system.iam.internal.origin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

@ConfigurationProperties("freesia.iam.ip-location")
public record LocationProperties(
        @DefaultValue("classpath:ip2region/ip2region_v4.xdb") Resource ipv4,
        @DefaultValue("classpath:ip2region/ip2region_v6.xdb") Resource ipv6) {}
