package com.example.attend.entity;


import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

public class LoginUser extends User {
    private String displayname;

    public LoginUser(String username,
                     String password,
                     Collection<? extends GrantedAuthority> authorities,
                     String displayname) {
        super(username, password, authorities);
        this.displayname = displayname;
    }

    public String getDisplayname() {
        return displayname;
    }
}
