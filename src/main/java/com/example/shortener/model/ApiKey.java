package com.example.shortener.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "api_keys")
public class ApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean admin = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ApiKey() {
    }

    public ApiKey(String keyHash, String name, boolean admin) {
        this.keyHash = keyHash;
        this.name = name;
        this.admin = admin;
    }

    public Long getId() {
        return id;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isAdmin() {
        return admin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void promote() {
        this.admin = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
