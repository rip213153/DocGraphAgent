package com.agenthub.model;

import java.util.List;

public class ExtractionResult {

    private List<Entity> entities;
    private List<Relation> relations;
    private String sourceChunkId;

    public ExtractionResult() {
    }

    public ExtractionResult(List<Entity> entities, List<Relation> relations, String sourceChunkId) {
        this.entities = entities;
        this.relations = relations;
        this.sourceChunkId = sourceChunkId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Entity> getEntities() {
        return entities;
    }

    public void setEntities(List<Entity> entities) {
        this.entities = entities;
    }

    public List<Relation> getRelations() {
        return relations;
    }

    public void setRelations(List<Relation> relations) {
        this.relations = relations;
    }

    public String getSourceChunkId() {
        return sourceChunkId;
    }

    public void setSourceChunkId(String sourceChunkId) {
        this.sourceChunkId = sourceChunkId;
    }

    public static final class Builder {
        private List<Entity> entities;
        private List<Relation> relations;
        private String sourceChunkId;

        private Builder() {
        }

        public Builder entities(List<Entity> entities) {
            this.entities = entities;
            return this;
        }

        public Builder relations(List<Relation> relations) {
            this.relations = relations;
            return this;
        }

        public Builder sourceChunkId(String sourceChunkId) {
            this.sourceChunkId = sourceChunkId;
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(entities, relations, sourceChunkId);
        }
    }

    public static class Entity {
        private String name;
        private String type;
        private String description;

        public Entity() {
        }

        public Entity(String name, String type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }

        public static EntityBuilder builder() {
            return new EntityBuilder();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public static final class EntityBuilder {
            private String name;
            private String type;
            private String description;

            private EntityBuilder() {
            }

            public EntityBuilder name(String name) {
                this.name = name;
                return this;
            }

            public EntityBuilder type(String type) {
                this.type = type;
                return this;
            }

            public EntityBuilder description(String description) {
                this.description = description;
                return this;
            }

            public Entity build() {
                return new Entity(name, type, description);
            }
        }
    }

    public static class Relation {
        private String head;
        private String relation;
        private String tail;
        private double confidence;

        public Relation() {
        }

        public Relation(String head, String relation, String tail, double confidence) {
            this.head = head;
            this.relation = relation;
            this.tail = tail;
            this.confidence = confidence;
        }

        public static RelationBuilder builder() {
            return new RelationBuilder();
        }

        public String getHead() {
            return head;
        }

        public void setHead(String head) {
            this.head = head;
        }

        public String getRelation() {
            return relation;
        }

        public void setRelation(String relation) {
            this.relation = relation;
        }

        public String getTail() {
            return tail;
        }

        public void setTail(String tail) {
            this.tail = tail;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public static final class RelationBuilder {
            private String head;
            private String relation;
            private String tail;
            private double confidence;

            private RelationBuilder() {
            }

            public RelationBuilder head(String head) {
                this.head = head;
                return this;
            }

            public RelationBuilder relation(String relation) {
                this.relation = relation;
                return this;
            }

            public RelationBuilder tail(String tail) {
                this.tail = tail;
                return this;
            }

            public RelationBuilder confidence(double confidence) {
                this.confidence = confidence;
                return this;
            }

            public Relation build() {
                return new Relation(head, relation, tail, confidence);
            }
        }
    }
}
