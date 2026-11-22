package com.freesia.system.iam.api;

import com.freesia.system.iam.internal.bootstrap.service.InitializeAdministrator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit process entry point; passwords are never accepted as arguments or printed. */
public final class BootstrapAdministratorCommand {
    private BootstrapAdministratorCommand() {}

    public static void run(Class<?> application, String[] arguments) {
        String username = option(arguments, "--username=");
        String file = option(arguments, "--password-file=");
        if (Arrays.stream(arguments).anyMatch(value -> value.startsWith("--password="))) {
            throw new IllegalArgumentException("Use hidden console input or --password-file, never a password argument");
        }
        char[] password = readSecret(file);
        if (password == null) {
            System.out.println("Administrator initialization cancelled.");
            return;
        }
        var app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        String[] configuration = Arrays.stream(arguments).skip(1)
                .filter(value -> !value.startsWith("--username=") && !value.startsWith("--password-file=")).toArray(String[]::new);
        try (var context = app.run(configuration)) {
            context.getBean(InitializeAdministrator.class).initialize(username, password);
            System.out.println("Administrator initialization completed.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String option(String[] arguments, String prefix) {
        return Arrays.stream(arguments).filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length())).findFirst().orElse(null);
    }

    private static char[] readSecret(String file) {
        if (file == null) {
            var console = System.console();
            if (console == null) throw new IllegalArgumentException("A console or --password-file is required");
            return readPassword(() -> console.readPassword("Initial administrator password: "),
                    message -> console.printf("%s%n", message));
        }
        byte[] contents = null;
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024) {
                throw new IllegalArgumentException("Secret must be a regular file of at most 1024 bytes");
            }
            contents = Files.readAllBytes(path);
            var decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(contents));
            char[] value = new char[decoded.remaining()];
            decoded.get(value);
            int end = value.length;
            if (end > 0 && value[end - 1] == '\n') end--;
            if (end > 0 && value[end - 1] == '\r') end--;
            char[] secret = Arrays.copyOf(value, end);
            Arrays.fill(value, '\0');
            return secret;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Cannot read the administrator secret file", failure);
        } finally {
            if (contents != null) Arrays.fill(contents, (byte) 0);
        }
    }

    static char[] readPassword(Supplier<char[]> input, Consumer<String> error) {
        while (true) {
            char[] password = input.get();
            if (password == null) return null;
            if (password.length > 0 && !java.nio.CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)) {
                return password;
            }
            Arrays.fill(password, '\0');
            error.accept("Password must not be empty or whitespace. Try again, or press Ctrl+C to cancel.");
        }
    }
}
