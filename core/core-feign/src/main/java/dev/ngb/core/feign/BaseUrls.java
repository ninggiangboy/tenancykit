package dev.ngb.core.feign;

/** The one spelling of a deployment's base URL: one without a trailing slash, and a missing one is empty. */
public final class BaseUrls {

    private BaseUrls() {}

    public static String withoutTrailingSlash(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
