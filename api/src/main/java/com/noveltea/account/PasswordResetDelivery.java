package com.noveltea.account;

import com.noveltea.mail.MailProperties;
import com.noveltea.mail.Mailer;
import org.springframework.stereotype.Component;

/**
 * How a reset link reaches its owner.
 *
 * <p>Kept as an interface so a deployment can substitute its own channel, and so tests can
 * capture the token without a mail server.
 */
public interface PasswordResetDelivery {

    void deliver(String email, String token);

    /** Sends through whichever {@link Mailer} is configured — SMTP, or the logging fallback. */
    @Component
    class MailDelivery implements PasswordResetDelivery {

        private final Mailer mailer;
        private final MailProperties properties;

        public MailDelivery(Mailer mailer, MailProperties properties) {
            this.mailer = mailer;
            this.properties = properties;
        }

        @Override
        public void deliver(String email, String token) {
            mailer.send(email, "Reset your NovelTea password", """
                    Someone asked to reset the password for this address.

                    %s

                    The link works once and expires in an hour. Resetting will sign out every
                    device on the account.

                    If this was not you, nothing has changed and you can ignore this message.
                    """.formatted(properties.passwordResetLink(token)));
        }
    }
}
