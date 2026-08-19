package com.noveltea.mail;

/**
 * Outbound mail.
 *
 * <p>An interface with two implementations chosen by configuration: a real sender when
 * SMTP is configured, and one that logs otherwise. Self-hosting must not require a mail
 * server to install, but it must be obvious when messages are going nowhere.
 */
public interface Mailer {

    void send(String to, String subject, String body);

    /** True when messages actually leave the machine. */
    boolean isDelivering();
}
