package com.agenthub.model;

public class QaAskRequest {

    private String sessionId;
    private String userId;
    private String question;

    public QaAskRequest() {
    }

    public QaAskRequest(String sessionId, String userId, String question) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
