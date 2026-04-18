package com.agenthub.model;

import java.util.List;
import java.util.Map;

public class QAResult {

    private String question;
    private String answer;
    private double confidence;
    private String intent;
    private boolean degraded;
    private List<String> degradeReasons;
    private String workflowState;
    private List<RetrievedContext> contexts;
    private List<String> reasoningSteps;

    public QAResult() {
    }

    public QAResult(String question, String answer, double confidence, String intent,
                    List<RetrievedContext> contexts, List<String> reasoningSteps) {
        this(question, answer, confidence, intent, false, List.of(), null, contexts, reasoningSteps);
    }

    public QAResult(String question, String answer, double confidence, String intent,
                    boolean degraded, List<String> degradeReasons, String workflowState,
                    List<RetrievedContext> contexts, List<String> reasoningSteps) {
        this.question = question;
        this.answer = answer;
        this.confidence = confidence;
        this.intent = intent;
        this.degraded = degraded;
        this.degradeReasons = degradeReasons;
        this.workflowState = workflowState;
        this.contexts = contexts;
        this.reasoningSteps = reasoningSteps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public List<String> getDegradeReasons() {
        return degradeReasons;
    }

    public void setDegradeReasons(List<String> degradeReasons) {
        this.degradeReasons = degradeReasons;
    }

    public String getWorkflowState() {
        return workflowState;
    }

    public void setWorkflowState(String workflowState) {
        this.workflowState = workflowState;
    }

    public List<RetrievedContext> getContexts() {
        return contexts;
    }

    public void setContexts(List<RetrievedContext> contexts) {
        this.contexts = contexts;
    }

    public List<String> getReasoningSteps() {
        return reasoningSteps;
    }

    public void setReasoningSteps(List<String> reasoningSteps) {
        this.reasoningSteps = reasoningSteps;
    }

    public static final class Builder {
        private String question;
        private String answer;
        private double confidence;
        private String intent;
        private boolean degraded;
        private List<String> degradeReasons = List.of();
        private String workflowState;
        private List<RetrievedContext> contexts;
        private List<String> reasoningSteps;

        private Builder() {
        }

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public Builder answer(String answer) {
            this.answer = answer;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder intent(String intent) {
            this.intent = intent;
            return this;
        }

        public Builder degraded(boolean degraded) {
            this.degraded = degraded;
            return this;
        }

        public Builder degradeReasons(List<String> degradeReasons) {
            this.degradeReasons = degradeReasons;
            return this;
        }

        public Builder workflowState(String workflowState) {
            this.workflowState = workflowState;
            return this;
        }

        public Builder contexts(List<RetrievedContext> contexts) {
            this.contexts = contexts;
            return this;
        }

        public Builder reasoningSteps(List<String> reasoningSteps) {
            this.reasoningSteps = reasoningSteps;
            return this;
        }

        public QAResult build() {
            return new QAResult(question, answer, confidence, intent, degraded, degradeReasons, workflowState, contexts, reasoningSteps);
        }
    }

    public static class RetrievedContext {
        private String content;
        private String source;
        private double score;
        private String retrievalType;
        private Map<String, Object> metadata;

        public RetrievedContext() {
        }

        public RetrievedContext(String content, String source, double score,
                                String retrievalType, Map<String, Object> metadata) {
            this.content = content;
            this.source = source;
            this.score = score;
            this.retrievalType = retrievalType;
            this.metadata = metadata;
        }

        public static RetrievedContextBuilder builder() {
            return new RetrievedContextBuilder();
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getRetrievalType() {
            return retrievalType;
        }

        public void setRetrievalType(String retrievalType) {
            this.retrievalType = retrievalType;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public static final class RetrievedContextBuilder {
            private String content;
            private String source;
            private double score;
            private String retrievalType;
            private Map<String, Object> metadata;

            private RetrievedContextBuilder() {
            }

            public RetrievedContextBuilder content(String content) {
                this.content = content;
                return this;
            }

            public RetrievedContextBuilder source(String source) {
                this.source = source;
                return this;
            }

            public RetrievedContextBuilder score(double score) {
                this.score = score;
                return this;
            }

            public RetrievedContextBuilder retrievalType(String retrievalType) {
                this.retrievalType = retrievalType;
                return this;
            }

            public RetrievedContextBuilder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public RetrievedContext build() {
                return new RetrievedContext(content, source, score, retrievalType, metadata);
            }
        }
    }
}
