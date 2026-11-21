package com.freesia.admin.iam.internal.account;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.util.UUID;

@Table(value = "sys_account", schema = "freesia")
public class AccountRow {
    @Id(keyType = KeyType.Auto) private UUID id;
    private String username;
    private String passwordHash;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
