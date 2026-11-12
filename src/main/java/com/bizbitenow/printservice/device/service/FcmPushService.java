package com.bizbitenow.printservice.device.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

@Service
@Slf4j
public class FcmPushService {
    public enum Result { SENT, INVALID_TOKEN, FAILED, NOT_CONFIGURED }

    @Value("${app.firebase.credentials:}")
    private String credentialPath;
    private FirebaseApp firebaseApp;

    @PostConstruct
    void initialize() throws IOException {
        if (credentialPath == null || credentialPath.isBlank()) {
            log.info("Firebase credentials are not configured; device polling remains enabled");
            return;
        }
        try (FileInputStream credentials = new FileInputStream(credentialPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentials)).build();
            firebaseApp = FirebaseApp.getApps().stream().filter(app -> app.getName().equals(FirebaseApp.DEFAULT_APP_NAME))
                    .findFirst().orElseGet(() -> FirebaseApp.initializeApp(options));
        }
    }

    public Result sendNewJob(String token, String jobId) {
        if (firebaseApp == null) return Result.NOT_CONFIGURED;
        Message message = Message.builder()
                .setToken(token)
                .putAllData(Map.of("type", "NEW_JOB", "jobId", jobId))
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                .build();
        try {
            FirebaseMessaging.getInstance(firebaseApp).send(message);
            return Result.SENT;
        } catch (FirebaseMessagingException ex) {
            if (ex.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || ex.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                log.warn("FCM rejected a device registration token");
                return Result.INVALID_TOKEN;
            }
            log.warn("FCM delivery failed: {}", ex.getMessagingErrorCode());
            return Result.FAILED;
        }
    }
}
