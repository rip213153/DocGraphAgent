package com.agenthub.memory;

public class MemoryContext {

    public static final String EMPTY_CONTEXT = "(无)";

    private String shortTermContext = EMPTY_CONTEXT;

    public MemoryContext() {
    }

    public MemoryContext(String shortTermContext) {
        this.shortTermContext = shortTermContext == null || shortTermContext.isBlank()
                ? EMPTY_CONTEXT
                : shortTermContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getShortTermContext() {
        return shortTermContext;
    }

    public void setShortTermContext(String shortTermContext) {
        this.shortTermContext = shortTermContext == null || shortTermContext.isBlank()
                ? EMPTY_CONTEXT
                : shortTermContext;
    }

    public boolean hasShortTermContext() {
        return shortTermContext != null && !shortTermContext.isBlank() && !EMPTY_CONTEXT.equals(shortTermContext);
    }

    public static final class Builder {
        private String shortTermContext = EMPTY_CONTEXT;

        private Builder() {
        }

        public Builder shortTermContext(String shortTermContext) {
            this.shortTermContext = shortTermContext;
            return this;
        }

        public MemoryContext build() {
            return new MemoryContext(shortTermContext);
        }
    }
}
