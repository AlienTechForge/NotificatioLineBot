package com.jason.notifyline.client;

public class ClientNotFoundException extends RuntimeException {
    public ClientNotFoundException(String clientId) {
        super("client not found: " + clientId);
    }
}
