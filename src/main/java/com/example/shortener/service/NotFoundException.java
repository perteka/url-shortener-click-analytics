package com.example.shortener.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
