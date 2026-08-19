package com.noveltea.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param from the envelope sender. Many providers reject mail whose From does not match an
 *     address they host, so this is worth setting deliberately rather than defaulting.
 * @param appUrl base URL of the browser client, used to build links people click. Without
 *     it a reset email can only carry a bare token.
 */
@ConfigurationProperties(prefix = "noveltea.mail")
public record MailProperties(String from, String appUrl) {

    public MailProperties {
        from = from == null || from.isBlank() ? "noveltea@localhost" : from;
        appUrl = appUrl == null || appUrl.isBlank() ? null : appUrl.replaceAll("/+$", "");
    }

    /** A clickable link when the app URL is known, and the bare token when it is not. */
    public String passwordResetLink(String token) {
        return appUrl == null ? token : appUrl + "/reset-password?token=" + token;
    }
}
