package com.kaplansoftware.imweb.service;

import com.kaplansoftware.imweb.model.ProductDetails;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;

public class ProductCache {

    private Jedis jedis;

    public ProductCache(String url, int port, String password) {
        jedis = new Jedis(url,port);
        jedis.auth(password);
    }

    private String makeKey(String productId, String accountId) {
        StringBuilder sb = new StringBuilder("product::");
        sb.append(productId);
        if (accountId != null) {
            sb.append("::");
            sb.append(accountId);
        }

        return sb.toString();
    }

    public void put(String productId, String accountId, ProductDetails details) {
        Map<String,String> hash = new HashMap<>();
        hash.put("name",details.getName());
        hash.put("roles",details.getRoles());

        jedis.hset(makeKey(productId,accountId),hash);
    }

    public void evict(String productId, String accountId) {
        jedis.del(makeKey(productId,accountId));
    }
}
