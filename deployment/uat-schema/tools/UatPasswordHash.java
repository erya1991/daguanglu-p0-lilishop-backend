import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UatPasswordHash {
    public static void main(String[] args) throws Exception {
        var encoder = new BCryptPasswordEncoder();
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = input.readLine()) != null;) {
                if (!line.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Expected native MD5 input");
                if (args.length == 1 && "verify".equals(args[0])) {
                    String hash = input.readLine();
                    if (hash == null) throw new IllegalArgumentException("Hash required");
                    System.out.println(encoder.matches(line, hash));
                } else {
                    System.out.println(encoder.encode(line));
                }
            }
        }
    }
}
