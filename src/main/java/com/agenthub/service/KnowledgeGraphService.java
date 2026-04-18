package com.agenthub.service;

import com.agenthub.model.ExtractionResult;
import com.agenthub.model.QAResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9_]{2,20}");
    private static final String ENTITY_KEY_FIELD = "entity_key";

    @Value("${app.neo4j.uri}")
    private String uri;
    @Value("${app.neo4j.user}")
    private String user;
    @Value("${app.neo4j.password}")
    private String password;

    private Driver driver;

    @PostConstruct
    public void init() {
        try {
            driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
            ensureIndexes();
        } catch (Exception e) {
            log.warn("Neo4j connection failed: {}", e.getMessage());
            driver = null;
        }
    }

    @PreDestroy
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }

    public void upsertEntity(ExtractionResult.Entity entity) {
        upsertEntity(entity, 1, "", "");
    }

    public void upsertEntity(ExtractionResult.Entity entity, int version, String source) {
        upsertEntity(entity, version, source, "");
    }

    public void upsertEntity(ExtractionResult.Entity entity, int version, String source, String chunkId) {
        String entityKey = buildEntityKey(entity.getName(), source);
        String cypher = """
                MERGE (e:Entity {entity_key: $entityKey})
                ON CREATE SET e.type = $type, e.description = $desc, e.source = $source,
                             e.name = $name, e.version = $version,
                             e.chunk_ids = CASE WHEN $chunkId = '' THEN [] ELSE [$chunkId] END,
                             e.created_at = timestamp(), e.updated_at = timestamp()
                ON MATCH SET e.description = CASE WHEN $desc <> '' THEN $desc ELSE e.description END,
                             e.name = $name,
                             e.type = CASE WHEN $type <> '' THEN $type ELSE e.type END,
                             e.source = CASE WHEN $source <> '' THEN $source ELSE e.source END,
                             e.chunk_ids = CASE
                                 WHEN $chunkId = '' THEN e.chunk_ids
                                 WHEN e.chunk_ids IS NULL THEN [$chunkId]
                                 WHEN $chunkId IN e.chunk_ids THEN e.chunk_ids
                                 ELSE e.chunk_ids + $chunkId
                             END,
                             e.version = $version,
                             e.updated_at = timestamp()
                """;
        try (Session session = requireDriver().session()) {
            session.run(cypher, Map.of(
                    "entityKey", entityKey,
                    "name", entity.getName(),
                    "type", entity.getType(),
                    "desc", entity.getDescription(),
                    "source", source,
                    "version", version,
                    "chunkId", chunkId
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upsert entity into knowledge graph", e);
        }
    }

    public void addRelation(ExtractionResult.Relation relation) {
        addRelation(relation, "", "", 1);
    }

    public void addRelation(ExtractionResult.Relation relation, String source) {
        addRelation(relation, source, "", 1);
    }

    public void addRelation(ExtractionResult.Relation relation, String source, String chunkId, int version) {
        String relType = relation.getRelation().toUpperCase().replace(" ", "_");
        String headKey = buildEntityKey(relation.getHead(), source);
        String tailKey = buildEntityKey(relation.getTail(), source);
        String cypher = String.format("""
                MATCH (h:Entity {entity_key: $headKey})
                MATCH (t:Entity {entity_key: $tailKey})
                MERGE (h)-[r:%s]->(t)
                SET r.confidence = $conf, r.source = $source, r.chunk_id = $chunkId,
                    r.version = $version, r.updated_at = timestamp()
                """, relType);
        try (Session session = requireDriver().session()) {
            session.run(cypher, Map.of(
                    "headKey", headKey,
                    "tailKey", tailKey,
                    "conf", relation.getConfidence(),
                    "source", source,
                    "chunkId", chunkId,
                    "version", version
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to add relation into knowledge graph", e);
        }
    }

    public List<QAResult.RetrievedContext> searchByQuestion(String question) {
        requireDriver();

        List<QAResult.RetrievedContext> results = new ArrayList<>();
        Set<String> candidateEntities = new LinkedHashSet<>();
        for (String keyword : extractCandidateKeywords(question)) {
            for (Map<String, Object> row : searchEntities(keyword, 5)) {
                Object entityKey = row.get(ENTITY_KEY_FIELD);
                if (entityKey != null) {
                    candidateEntities.add(String.valueOf(entityKey));
                }
                if (candidateEntities.size() >= 5) {
                    break;
                }
            }
            if (candidateEntities.size() >= 5) {
                break;
            }
        }

        for (String entityKey : candidateEntities) {
            for (Map<String, Object> row : getNeighbors(entityKey, 2, 20)) {
                @SuppressWarnings("unchecked")
                List<String> relations = (List<String>) row.getOrDefault("relations", List.of("related"));
                String content = row.getOrDefault("sourceName", "") + " --["
                        + String.join(" -> ", relations)
                        + "]--> " + row.getOrDefault("target", "") + " ("
                        + row.getOrDefault("targetType", "") + ")";
                results.add(QAResult.RetrievedContext.builder()
                        .content(content)
                        .source("knowledge_graph")
                        .score(0.8)
                        .retrievalType("graph")
                        .metadata(Map.of(
                                "entityKey", entityKey,
                                "hops", 2,
                                "source", row.getOrDefault("sourceRef", "")
                        ))
                        .build());
            }
        }
        return results;
    }

    public List<Map<String, Object>> searchEntities(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String cypher = """
                MATCH (e:Entity)
                WHERE e.name CONTAINS $keyword OR e.description CONTAINS $keyword
                RETURN e.entity_key AS entity_key, e.name AS name, e.type AS type,
                       e.description AS description, e.source AS source
                LIMIT $limit
                """;
        try (Session session = requireDriver().session()) {
            return session.run(cypher, Map.of("keyword", keyword, "limit", limit))
                    .list(record -> record.asMap());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to search entities from knowledge graph", e);
        }
    }

    public List<Map<String, Object>> getNeighbors(String entityKey, int hops, int limit) {
        String cypher = """
                MATCH path = (start:Entity {entity_key: $entityKey})-[*1..%d]-(neighbor:Entity)
                RETURN start.name AS sourceName,
                       start.source AS sourceRef,
                       [r IN relationships(path) | type(r)] AS relations,
                       neighbor.name AS target,
                       neighbor.type AS targetType
                LIMIT $limit
                """.formatted(hops);
        try (Session session = requireDriver().session()) {
            return session.run(cypher, Map.of("entityKey", entityKey, "limit", limit))
                    .list(record -> record.asMap());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load graph neighbors", e);
        }
    }

    public void deleteBySource(String source) {
        try (Session session = requireDriver().session()) {
            session.run("MATCH ()-[r {source: $source}]-() DELETE r", Map.of("source", source));
            session.run("MATCH (e:Entity {source: $source}) DETACH DELETE e", Map.of("source", source));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete knowledge graph data by source", e);
        }
    }

    public void deleteBySourceAndChunkIds(String source, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try (Session session = requireDriver().session()) {
            session.run("""
                    MATCH ()-[r]-()
                    WHERE r.source = $source AND r.chunk_id IN $chunkIds
                    DELETE r
                    """, Map.of("source", source, "chunkIds", chunkIds));
            session.run("""
                    MATCH (e:Entity {source: $source})
                    WHERE any(chunkId IN coalesce(e.chunk_ids, []) WHERE chunkId IN $chunkIds)
                    SET e.chunk_ids = [chunkId IN coalesce(e.chunk_ids, []) WHERE NOT chunkId IN $chunkIds]
                    WITH e
                    WHERE size(coalesce(e.chunk_ids, [])) = 0
                    DETACH DELETE e
                    """, Map.of("source", source, "chunkIds", chunkIds));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete knowledge graph data by source and chunk", e);
        }
    }

    public Map<String, Object> getStats() {
        if (driver == null) {
            return Map.of("status", "disconnected");
        }
        try (Session session = driver.session()) {
            long entities = session.run("MATCH (e:Entity) RETURN count(e) AS cnt")
                    .single().get("cnt").asLong();
            long relations = session.run("MATCH ()-[r]->() RETURN count(r) AS cnt")
                    .single().get("cnt").asLong();
            return Map.of("totalEntities", entities, "totalRelations", relations);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    static String buildEntityKey(String entityName, String source) {
        return normalizeKeyPart(source) + "::" + normalizeKeyPart(entityName);
    }

    private void ensureIndexes() {
        if (driver == null) {
            return;
        }
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (n:Entity) REQUIRE n.entity_key IS UNIQUE");
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.name)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.type)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.source)");
        }
    }

    private Driver requireDriver() {
        if (driver == null) {
            throw new IllegalStateException("Knowledge graph is unavailable");
        }
        return driver;
    }

    private List<String> extractCandidateKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        Set<String> keywords = new LinkedHashSet<>();
        String normalized = question.trim();
        keywords.add(normalized.length() > 20 ? normalized.substring(0, 20) : normalized);

        Matcher matcher = KEYWORD_PATTERN.matcher(normalized);
        while (matcher.find() && keywords.size() < 8) {
            String token = matcher.group();
            keywords.add(token);
            if (containsChinese(token) && token.length() > 4) {
                keywords.addAll(expandChineseToken(token));
            }
        }
        return keywords.stream().limit(8).toList();
    }

    private boolean containsChinese(String token) {
        return token.chars().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
    }

    private List<String> expandChineseToken(String token) {
        List<String> expanded = new ArrayList<>();
        for (int window = 2; window <= 3; window++) {
            for (int i = 0; i + window <= token.length() && expanded.size() < 6; i++) {
                expanded.add(token.substring(i, i + window));
            }
        }
        return expanded;
    }

    private static String normalizeKeyPart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9]+", "_");
    }
}
