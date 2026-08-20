package com.naraesigning.board.core;

public record BoardShare(int version, String shareToken) {
    @Override
    public String toString() {
        return "BoardShare[version=" + version + ", shareToken=[REDACTED]]";
    }
}
