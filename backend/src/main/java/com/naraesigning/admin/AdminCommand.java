package com.naraesigning.admin;

import java.io.PrintWriter;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public final class AdminCommand implements ApplicationRunner {
    private final AdminUserService users;
    private final AdminConsole console;
    private final PrintWriter output;

    public AdminCommand(AdminUserService users) {
        this(users, prompt -> {
            var systemConsole = System.console();
            if (systemConsole == null) {
                throw new IllegalStateException("Administrator commands require an interactive console");
            }
            return systemConsole.readPassword("%s", prompt);
        }, new PrintWriter(System.out, true));
    }

    AdminCommand(AdminUserService users, AdminConsole console, PrintWriter output) {
        this.users = users;
        this.console = console;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!arguments.getOptionNames().isEmpty()) {
            throw new IllegalArgumentException("Administrator commands do not accept options");
        }
        execute(arguments.getNonOptionArgs());
    }

    void execute(List<String> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        if (arguments.size() != 2
                || !(arguments.getFirst().equals("create-admin")
                        || arguments.getFirst().equals("reset-password"))) {
            throw new IllegalArgumentException("Usage: create-admin|reset-password <email>");
        }

        AdminEmail email = AdminEmail.parse(arguments.get(1));
        char[] raw = console.readPassword("Password: ");
        if (raw == null) {
            throw new IllegalArgumentException("Password entry cancelled");
        }
        AdminPassword parsed = null;
        try {
            parsed = AdminPassword.parse(raw);
            if (arguments.getFirst().equals("create-admin")) {
                users.create(email, parsed);
                output.println("Administrator created: " + email.value());
            } else {
                int invalidated = users.resetPassword(email, parsed);
                output.println("Administrator password reset; sessions invalidated: " + invalidated);
            }
        } finally {
            java.util.Arrays.fill(raw, '\0');
            if (parsed != null) {
                parsed.clear();
            }
        }
    }
}
