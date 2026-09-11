package com.elioo.healthcare.core.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${firebase.config.path:classpath:firebase/firebase-adminsdk.json}")
    private Resource firebaseConfig;

    @Value("${firebase.enabled:true}")
    private boolean firebaseEnabled;

    @PostConstruct
    public void initializeFirebase() {
        if (!firebaseEnabled) {
            log.warn("⚠️ Firebase is disabled via configuration (firebase.enabled=false)");
            return;
        }

        if (!firebaseConfig.exists()) {
            log.warn("⚠️ Firebase credentials file not found at: {}. Skipping Firebase initialization. " +
                    "Set firebase.enabled=false to suppress this warning.", firebaseConfig.getDescription());
            return;
        }

        try {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(firebaseConfig.getInputStream()))
                .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                log.info("✅ Firebase initialized successfully.");
            } else {
                log.info("⚠️ Firebase already initialized.");
            }
        } catch (IOException e) {
            log.error("❌ Failed to initialize Firebase", e);
            log.warn("⚠️ Continuing without Firebase. Set firebase.enabled=false to disable Firebase completely.");
            // Don't throw exception - allow application to start without Firebase
        }
    }
}
