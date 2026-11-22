package com.freesia.system.dashboard.internal.product.controller;

import com.freesia.common.result.R;
import com.freesia.system.dashboard.internal.product.dto.ProductInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductInfoController {
    private final ProductInfo info;

    ProductInfoController(@Value("${freesia.product.name}") String productName,
                          @Value("${freesia.product.admin-name}") String adminName,
                          @Value("${freesia.product.version}") String version) {
        this.info = new ProductInfo(productName, adminName, version);
    }

    @GetMapping("/api/product")
    R<ProductInfo> product() {
        return R.ok(info);
    }
}
