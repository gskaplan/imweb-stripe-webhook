package com.kaplansoftware.imweb.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;

@RequiredArgsConstructor
public class UserRepository {
    private final MongoDatabase mongoDatabase;

    public Optional<Document> findByEmail(String email) {
        MongoCollection<Document> users = mongoDatabase.getCollection("user");
        Document user = users.find(eq("email",email.trim())).first();
        return user == null ? Optional.empty() : Optional.of(user);
    }

    public Optional<Document> findById(ObjectId id) {
        MongoCollection<Document> users = mongoDatabase.getCollection("user");
        Document user = users.find(eq("_id",id)).first();
        return user == null ? Optional.empty() : Optional.of(user);
    }

    public void update(Map<String,Object> selectionCriteria, Map<String,Object> data) {
        MongoCollection<Document> users = mongoDatabase.getCollection("user");

        List<Bson> selection = selectionCriteria.entrySet().stream()
                .map(e->eq(e.getKey(),e.getValue()))
                .collect(Collectors.toList());

        List<Bson> sets = data.entrySet().stream()
                .map(e-> Updates.set(e.getKey(),e.getValue()))
                .collect(Collectors.toList());

        Bson criteria =  Filters.and(selection);
        Bson values = Updates.combine(sets);

        users.updateOne(criteria,values);
    }


}
