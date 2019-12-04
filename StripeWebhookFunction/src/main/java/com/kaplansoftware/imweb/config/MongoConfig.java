package com.kaplansoftware.imweb.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.connection.ConnectionPoolSettings;
import com.mongodb.connection.SocketSettings;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.concurrent.TimeUnit;

public class MongoConfig {

    public static MongoClient mongoClient(String connectionString) {
        ConnectionString connStr = new ConnectionString(connectionString);

        ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder()
                .minSize(2)
                .maxSize(20)
                .maxWaitQueueSize(100)
                .maxConnectionIdleTime(60, TimeUnit.SECONDS)
                .maxConnectionLifeTime(300, TimeUnit.SECONDS)
                .build();

        SocketSettings socketSettings = SocketSettings.builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        MongoClientSettings clientSettings = MongoClientSettings.builder()
                .applyConnectionString(connStr)
                .applyToConnectionPoolSettings(builder -> builder.applySettings(connectionPoolSettings))
                .applyToSocketSettings(builder -> builder.applySettings(socketSettings))
                .build();

        return MongoClients.create(clientSettings);
    }

    public static MongoDatabase mongoDatabase(MongoClient mongoClient) {
        CodecRegistry defaultCodecRegistry = MongoClientSettings.getDefaultCodecRegistry();
        CodecRegistry fromProvider = CodecRegistries.fromProviders(PojoCodecProvider.builder()
                .automatic(true)
                .build());
        CodecRegistry pojoCodecRegistry = CodecRegistries.fromRegistries(defaultCodecRegistry, fromProvider);
        return mongoClient.getDatabase("imweb-dev").withCodecRegistry(pojoCodecRegistry);
    }

}
