package com.novel.agent.security;


public class AdminSecurityProperties {

    private String adminApiKey = "";

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    public boolean isConfigured() {
        return adminApiKey != null && !adminApiKey.isBlank();
    }
}
