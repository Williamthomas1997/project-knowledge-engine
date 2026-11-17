package com.omnicore.pke.engine.security;

import com.omnicore.pke.engine.graph.ApiKeyStore.StoredKey;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The key CLI ({@code bin/pke-keys.sh}): runs instead of the server when {@code pke.cli=keys}, then exits.
 * <pre>
 *   create &lt;name&gt; &lt;ROLE[,ROLE]&gt;   prints the new key once (only its hash is stored)
 *   list                              names, roles, prefixes, creation and revocation times
 *   revoke &lt;name&gt;                   revokes every active key with that name
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "pke.cli", havingValue = "keys")
class ApiKeyCli implements ApplicationRunner {

    private final ApiKeyService keys;
    private final ConfigurableApplicationContext context;

    ApiKeyCli(ApiKeyService keys, ConfigurableApplicationContext context) {
        this.keys = keys;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = execute(args.getNonOptionArgs(), System.out, System.err);
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** Runs one command; returns the exit code. The key is printed alone on its line, so scripts can capture it. */
    int execute(List<String> args, PrintStream out, PrintStream err) {
        String command = args.isEmpty() ? "" : args.getFirst();
        try {
            switch (command) {
                case "create" -> {
                    if (args.size() != 3) {
                        return usage(err);
                    }
                    List<Role> roles = Arrays.stream(args.get(2).split(","))
                            .map(r -> Role.valueOf(r.strip().toUpperCase(Locale.ROOT))).toList();
                    ApiKeyService.CreatedKey created = keys.create(args.get(1), roles, "cli");
                    err.println("Created API key '" + created.name() + "' " + created.roles()
                            + ". It is shown once and only its hash is stored:");
                    out.println(created.key());
                    return 0;
                }
                case "list" -> {
                    for (StoredKey k : keys.list()) {
                        out.println(k.name() + "\t" + String.join(",", k.roles()) + "\t" + k.prefix() + "...\tcreated "
                                + k.createdAt() + (k.revokedAt() == null ? "" : "\trevoked " + k.revokedAt()));
                    }
                    return 0;
                }
                case "revoke" -> {
                    if (args.size() != 2) {
                        return usage(err);
                    }
                    err.println("Revoked " + keys.revoke(args.get(1), "cli") + " key(s) named '" + args.get(1) + "'");
                    return 0;
                }
                default -> {
                    return usage(err);
                }
            }
        } catch (RuntimeException e) {
            err.println("Failed: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err) {
        err.println("Usage: pke-keys.sh create <name> <READER|CONTRIBUTOR|SCHEMA_ADMIN|EVOLUTION_OPERATOR>[,...]"
                + " | list | revoke <name>");
        return 2;
    }
}
