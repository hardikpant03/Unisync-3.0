package com.unisync.backend.features.authentication.service;


import com.unisync.backend.features.authentication.dto.AuthenticationRequestBody;
import com.unisync.backend.features.authentication.dto.AuthenticationResponseBody;
import com.unisync.backend.features.authentication.model.User;
import com.unisync.backend.features.authentication.repository.UserRepository;
import com.unisync.backend.features.authentication.utils.EmailService;
import com.unisync.backend.features.authentication.utils.Encoder;
import com.unisync.backend.features.authentication.utils.JsonWebToken;
import com.unisync.backend.features.authentication.utils.TokenBlacklistUtil;
import com.unisync.backend.features.feed.Constant;
import com.unisync.backend.features.storage.service.StorageService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AuthenticationService {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    private final UserRepository userRepository;
    private final int durationInMinutes = 1;
    private final Encoder encoder;
    private final JsonWebToken jsonWebToken;
    private final EmailService emailService;
    private final RestTemplate restTemplate;
    private final StorageService storageService;
    private final TokenBlacklistUtil tokenBlacklistUtil;


    @PersistenceContext
    private EntityManager entityManager;

    private final String googleClientId = System.getenv("OAUTH_GOOGLE_CLIENT_ID");
    private final String googleClientSecret = System.getenv("OAUTH_GOOGLE_CLIENT_SECRET");


    public AuthenticationService(UserRepository userRepository, Encoder encoder, JsonWebToken jsonWebToken,
            EmailService emailService, RestTemplate restTemplate,TokenBlacklistUtil tokenBlacklistUtil) {
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.jsonWebToken = jsonWebToken;
        this.emailService = emailService;
        this.restTemplate = restTemplate;
        this.tokenBlacklistUtil = tokenBlacklistUtil;
        this.storageService = new StorageService();
    }


    public static String generateEmailVerificationToken() {
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            token.append(random.nextInt(10));
        }
        return token.toString();
    }

    public void sendEmailVerificationToken(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent() && Boolean.TRUE.equals(!user.get().getEmailVerified())) {
            String emailVerificationToken = generateEmailVerificationToken();
            String hashedToken = encoder.encode(emailVerificationToken);
            user.get().setEmailVerificationToken(hashedToken);
            user.get().setEmailVerificationTokenExpiryDate(LocalDateTime.now().plusMinutes(durationInMinutes));
            userRepository.save(user.get());
            String subject = "Email Verification";
            String body = String.format("Only one step to take full advantage of LinkedIn.\n\n" +
                            "Enter this code to verify your email: " +
                            "%s\n\n" + "The code will expire in " +
                            "%s" +
                            " minutes.",
                    emailVerificationToken, durationInMinutes);
            try {
                emailService.sendEmail(email, subject, body);
            } catch (Exception e) {
                logger.info("Error while sending email: {}", e.getMessage());
            }
        } else {
            throw new IllegalArgumentException("Email verification token failed, or email is already verified.");
        }
    }

    public void validateEmailVerificationToken(String token, String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent() && encoder.matches(token, user.get().getEmailVerificationToken())
                && !user.get().getEmailVerificationTokenExpiryDate().isBefore(LocalDateTime.now())) {
            user.get().setEmailVerified(true);
            user.get().setEmailVerificationToken(null);
            user.get().setEmailVerificationTokenExpiryDate(null);
            userRepository.save(user.get());
        } else if (user.isPresent() && encoder.matches(token, user.get().getEmailVerificationToken())
                && user.get().getEmailVerificationTokenExpiryDate().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Email verification token expired.");
        } else {
            throw new IllegalArgumentException("Email verification token failed.");
        }
    }

    public AuthenticationResponseBody login(AuthenticationRequestBody loginRequestBody) {
        User user = userRepository.findByEmail(loginRequestBody.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (!encoder.matches(loginRequestBody.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Password is incorrect.");
        }
        String token = jsonWebToken.generateToken(loginRequestBody.getEmail());
        String refreshToken = jsonWebToken.generateRefreshToken(loginRequestBody.getEmail());
        return new AuthenticationResponseBody(token,refreshToken,"Authentication succeeded.");
    }

    public AuthenticationResponseBody googleLoginOrSignup(String code, String page) {
        String tokenEndpoint = "https://oauth2.googleapis.com/token";
        String redirectUri = "http://localhost:5173/authentication/" + page; // Ensure this matches exactly

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                tokenEndpoint,
                HttpMethod.POST,
                request,
                new ParameterizedTypeReference<>() {}
        );

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new IllegalArgumentException("Failed to exchange code for token.");
        }

        Map<String, Object> responseBody = response.getBody();
        String idToken = (String) responseBody.get("id_token");
        if (idToken == null) {
            throw new IllegalArgumentException("No ID token received from Google.");
        }

        Claims claims = jsonWebToken.getClaimsFromGoogleOauthIdToken(idToken);
        String email = claims.get("email", String.class);
        if (email == null) {
            throw new IllegalArgumentException("No email found in ID token.");
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = new User(email, null);
            newUser.setEmailVerified(claims.get("email_verified", Boolean.class));
            newUser.setFirstName(claims.get("given_name", String.class));
            newUser.setLastName(claims.get("family_name", String.class));
            return userRepository.save(newUser);
        });

        String token = jsonWebToken.generateToken(user.getEmail());
        String refreshToken = jsonWebToken.generateRefreshToken(user.getEmail());
        return new AuthenticationResponseBody(token, refreshToken, "Google authentication succeeded.");
    }


    public AuthenticationResponseBody register(AuthenticationRequestBody registerRequestBody) {
        User user = userRepository.save(new User(
                registerRequestBody.getEmail(), encoder.encode(registerRequestBody.getPassword())));

        String emailVerificationToken = generateEmailVerificationToken();
        String hashedToken = encoder.encode(emailVerificationToken);
        user.setEmailVerificationToken(hashedToken);
        user.setEmailVerificationTokenExpiryDate(LocalDateTime.now().plusMinutes(durationInMinutes));

        userRepository.save(user);

        String subject = "Email Verification";
        String body = String.format("""
                Only one step to take full advantage of LinkedIn.

                Enter this code to verify your email: %s. The code will expire in %s minutes.""",
                emailVerificationToken, durationInMinutes);
        try {
            emailService.sendEmail(registerRequestBody.getEmail(), subject, body);
        } catch (Exception e) {
            logger.info("Error while sending email: {}", e.getMessage());
        }
        String authToken = jsonWebToken.generateToken(registerRequestBody.getEmail());
        String refreshToken = jsonWebToken.generateRefreshToken(registerRequestBody.getEmail());
        return new AuthenticationResponseBody(authToken, refreshToken,"User registered successfully.");
    }

    public Map<String, String> refreshToken(HttpServletRequest request) {
        String oldRefreshToken = null;

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (Constant.REFRESH_TOKEN.equals(cookie.getName())) {
                    oldRefreshToken = cookie.getValue();
                    break;
                }
            }
        }

        if (oldRefreshToken == null) {
            log.info("Missing refresh token in cookies");
            throw new JwtException("Missing Refresh Token");
        }

        try {
            String email = jsonWebToken.getEmailFromToken(oldRefreshToken);


            if (jsonWebToken.isTokenExpired(oldRefreshToken)) {
                tokenBlacklistUtil.blacklistToken(oldRefreshToken);
                log.info("Refresh token expired: {}", oldRefreshToken);
                throw new ExpiredJwtException(null, null, "Refresh token expired");
            }

            tokenBlacklistUtil.blacklistToken(oldRefreshToken);

            String newAccessToken = jsonWebToken.generateToken(email);
            String newRefreshToken = jsonWebToken.generateRefreshToken(email);

            return Map.of(
                    Constant.ACCESS_TOKEN, newAccessToken,
                    Constant.REFRESH_TOKEN, newRefreshToken
            );

        }
        catch (JwtException e) {
            tokenBlacklistUtil.blacklistToken(oldRefreshToken);
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException("Invalid refresh token");
        }
    }

    public User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = entityManager.find(User.class, userId);
        if (user != null) {
            entityManager.createNativeQuery("DELETE FROM posts_likes WHERE user_id = ?")
                    .setParameter(1, user.getId())
                    .executeUpdate();

            entityManager.remove(user);
        }
    }

    public void sendPasswordResetToken(String email) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent()) {
            String passwordResetToken = generateEmailVerificationToken();
            String hashedToken = encoder.encode(passwordResetToken);
            user.get().setPasswordResetToken(hashedToken);
            user.get().setPasswordResetTokenExpiryDate(LocalDateTime.now().plusMinutes(durationInMinutes));
            userRepository.save(user.get());
            String subject = "Password Reset";
            String body = String.format("""
                    You requested a password reset.

                    Enter this code to reset your password: %s. The code will expire in %s minutes.""",
                    passwordResetToken, durationInMinutes);
            try {
                emailService.sendEmail(email, subject, body);
            } catch (Exception e) {
                logger.info("Error while sending email: {}", e.getMessage());
            }
        } else {
            throw new IllegalArgumentException("User not found.");
        }
    }

    public void resetPassword(String email, String newPassword, String token) {
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent() && encoder.matches(token, user.get().getPasswordResetToken())
                && !user.get().getPasswordResetTokenExpiryDate().isBefore(LocalDateTime.now())) {
            user.get().setPasswordResetToken(null);
            user.get().setPasswordResetTokenExpiryDate(null);
            user.get().setPassword(encoder.encode(newPassword));
            userRepository.save(user.get());
        } else if (user.isPresent() && encoder.matches(token, user.get().getPasswordResetToken())
                && user.get().getPasswordResetTokenExpiryDate().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Password reset token expired.");
        } else {
            throw new IllegalArgumentException("Password reset token failed.");
        }
    }

    public User updateUserProfile(User user, String firstName, String lastName, String company,
            String position, String location, String about) {


        if (firstName != null)
            user.setFirstName(firstName);
        if (lastName != null)
            user.setLastName(lastName);
        if (company != null)
            user.setCompany(company);
        if (position != null)
            user.setPosition(position);
        if (location != null)
            user.setLocation(location);
        if (about != null)
            user.setAbout(about);
        user.setProfileComplete(true);
        return userRepository.save(user);


    }

    public User updateProfilePicture(User user, MultipartFile profilePicture) throws IOException {
        if (profilePicture != null) {
            String profilePictureUrl = storageService.saveImage(profilePicture);
            user.setProfilePicture(profilePictureUrl);
        } else {
            if (user.getProfilePicture() != null)
                storageService.deleteFile(user.getProfilePicture());

            user.setProfilePicture("");
        }

        return userRepository.save(user);
    }

    public User updateCoverPicture(User user, MultipartFile coverPicture) throws IOException {
        if (coverPicture != null) {
            String coverPictureUrl = storageService.saveImage(coverPicture);
            user.setCoverPicture(coverPictureUrl);
        } else {
            if (user.getCoverPicture() != null)
                storageService.deleteFile(user.getCoverPicture());
            user.setCoverPicture("");
        }
     return  userRepository.save(user);


    }

    public User getUserById(Long receiverId) {
        return userRepository.findById(receiverId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
    }
    
}