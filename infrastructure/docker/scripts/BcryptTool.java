import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Repo-supported BCrypt hasher for owner-account provisioning.
 *
 * Reads the owner password from OWNER_PASSWORD, never from command-line
 * arguments. Uses the same BCrypt implementation as the application.
 */
public class BcryptTool {

    public static void main(String[] args) {
        String password = System.getenv("OWNER_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("OWNER_PASSWORD environment variable is required.");
            System.exit(2);
        }
        System.out.print(new BCryptPasswordEncoder(10).encode(password));
    }
}