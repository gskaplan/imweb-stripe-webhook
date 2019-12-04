package com.kaplansoftware.imweb;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaplansoftware.imweb.config.MongoConfig;
import com.kaplansoftware.imweb.config.StripeConfig;
import com.kaplansoftware.imweb.service.ProductCache;
import com.kaplansoftware.imweb.service.UserRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.stripe.model.Event;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class StripeWebhookHandler implements RequestStreamHandler {

    private final boolean liveMode = Boolean.parseBoolean(System.getenv("livemode"));
    private final String appIdKey = "im_localid";
    private final MongoClient mongoClient = MongoConfig.mongoClient(System.getenv("mongoConnectionString"));
    private final MongoDatabase mongoDatabase = MongoConfig.mongoDatabase(mongoClient);
    private final ProductCache productCache = new ProductCache("redis-13572.c15.us-east-1-2.ec2.cloud.redislabs.com",13572,"RLG8tzqIZSu2zXO6dwpC5INeaEusplOw");

    private final StripeConfig stripeConfig = StripeConfig.builder()
            .privateKey(System.getenv("stripePK"))
            .salt(System.getenv("cryptoSalt"))
            .build();

    private final String stripePrivateKey = System.getenv("stripePK");

    private final UserRepository userRepository = new UserRepository(mongoDatabase);

    StripeWebhookController controller = new StripeWebhookController(liveMode,appIdKey,userRepository,stripeConfig,productCache);

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {

        Reader reader = new InputStreamReader(inputStream);
        Event event = Event.GSON.fromJson(reader, Event.class);

        controller.handleEvent(event);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");
        String output = String.format("{ \"message\": \"hello world\", \"EventID\": \"%s\" }", event.getId());

        GatewayResponse resp = new GatewayResponse(output, headers, 200);
        PrintStream printStream = new PrintStream(outputStream);
        Gson gson = new GsonBuilder().create();
        gson.toJson(resp,printStream);
    }
}
