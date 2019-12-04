package com.kaplansoftware.imweb.config;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Builder
@Data
public class StripeConfig {
    private final String privateKey;
    private final String salt;
}
