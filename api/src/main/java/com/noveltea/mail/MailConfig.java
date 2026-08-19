package com.noveltea.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses a mailer once, at startup.
 *
 * <p>One bean decided at runtime rather than two competing conditional ones:
 * {@code @ConditionalOnMissingBean} is only dependable inside auto-configuration, and using
 * it here produced a bean definition clash instead of a fallback.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public Mailer mailer(
            ObjectProvider<JavaMailSender> senders,
            MailProperties properties,
            @Value("${spring.mail.host:}") String host) {

        JavaMailSender sender = senders.getIfAvailable();
        if (host == null || host.isBlank() || sender == null) {
            log.warn("No SMTP server is configured. Password reset links and comment "
                    + "notifications will be written to the log instead of being sent, where "
                    + "anyone with log access can read them. Set spring.mail.host to send mail.");
            return new LoggingMailer();
        }
        log.info("Sending mail through {} as {}", host, properties.from());
        return new SmtpMailer(sender, properties);
    }

    /**
     * The fallback when no SMTP server is configured.
     *
     * <p>Self-hosting must not require a mail server to install, but it must be obvious that
     * messages are going nowhere — a reset link in a log file is a credential.
     */
    static class LoggingMailer implements Mailer {
        @Override
        public void send(String to, String subject, String body) {
            log.warn("Mail not sent (no SMTP configured). To: {} | {} | {}", to, subject, body);
        }

        @Override
        public boolean isDelivering() {
            return false;
        }
    }

    static class SmtpMailer implements Mailer {
        private final JavaMailSender sender;
        private final MailProperties properties;

        SmtpMailer(JavaMailSender sender, MailProperties properties) {
            this.sender = sender;
            this.properties = properties;
        }

        @Override
        public void send(String to, String subject, String body) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.from());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            try {
                sender.send(message);
            } catch (Exception e) {
                // A failed notification must never fail the action that triggered it: a
                // comment is saved whether or not anyone could be told about it.
                log.error("could not send mail to {}: {}", to, e.getMessage());
            }
        }

        @Override
        public boolean isDelivering() {
            return true;
        }
    }
}
