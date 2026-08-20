package com.naraesigning.admin;

import com.naraesigning.NaraeSigningApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public final class AdminCommandApplication {
    private AdminCommandApplication() {}

    public static void main(String[] arguments) {
        var application = new SpringApplication(NaraeSigningApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var ignored = application.run(arguments)) {
            // The operator command has completed; closing makes this a one-shot process.
        }
    }
}
