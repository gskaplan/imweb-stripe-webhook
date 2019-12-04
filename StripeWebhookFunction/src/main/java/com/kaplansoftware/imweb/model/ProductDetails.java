package com.kaplansoftware.imweb.model;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ProductDetails {
    private String name;
    private String roles;
}
