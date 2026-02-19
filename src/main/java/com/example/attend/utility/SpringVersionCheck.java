package com.example.attend.utility;


import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;
import org.springframework.security.core.SpringSecurityCoreVersion;

public class SpringVersionCheck {
    public static void main(String[] args) {
        String springVersion = SpringVersion.getVersion();
        System.out.println("스프링 프레임워크 버전: " + springVersion);

        String bootVersion = SpringBootVersion.getVersion();
        System.out.println("스프링 부트 버전: " + bootVersion);

        String securityVersion = SpringSecurityCoreVersion.getVersion();
        System.out.println("스프링 시큐리티 버전: " + securityVersion);
    }
}
