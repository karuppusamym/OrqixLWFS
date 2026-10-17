package com.orqix.runtime.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Operator-configured SMTP only. Definitions cannot change server, credentials, sender or application origin. */
@Component
public class StageMailTransport {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final boolean enabled;
    private final String from;
    public StageMailTransport(@Value("${orqix.mail.enabled:false}") boolean enabled,
            @Value("${orqix.mail.host:localhost}") String host, @Value("${orqix.mail.port:1025}") int port,
            @Value("${orqix.mail.from:workflow@example.invalid}") String from,
            @Value("${orqix.mail.username:}") String username, @Value("${orqix.mail.password:}") String password,
            @Value("${orqix.mail.starttls:false}") boolean tls) {
        this.enabled=enabled; this.from=from;
        sender.setHost(host); sender.setPort(port); sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (!username.isBlank()) { sender.setUsername(username); sender.setPassword(password); }
        var p=sender.getJavaMailProperties();
        p.setProperty("mail.smtp.auth",Boolean.toString(!username.isBlank()));
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(tls));
        p.setProperty("mail.smtp.starttls.required",Boolean.toString(tls));
        p.setProperty("mail.smtp.connectiontimeout","3000"); p.setProperty("mail.smtp.timeout","3000");
        p.setProperty("mail.smtp.writetimeout","3000");
    }
    public boolean enabled() { return enabled; }
    public void send(UUID id, String to, String subject, String body) throws MessagingException {
        InternetAddress address=new InternetAddress(to,true); address.validate();
        if (to.contains("\r") || to.contains("\n") || subject.contains("\r") || subject.contains("\n"))
            throw new MessagingException("Invalid mail header");
        MimeMessage message=new MimeMessage(sender.getSession()) {
            @Override protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID","<"+id+"@orqix.local>");
            }
        };
        message.setFrom(new InternetAddress(from,true));
        message.setRecipient(jakarta.mail.Message.RecipientType.TO,address);
        message.setSubject(subject,StandardCharsets.UTF_8.name());
        // Plain text only: template values never become executable HTML.
        message.setText(body,StandardCharsets.UTF_8.name());
        message.setHeader("X-Orqix-Notification",id.toString());
        sender.send(message);
    }
}
