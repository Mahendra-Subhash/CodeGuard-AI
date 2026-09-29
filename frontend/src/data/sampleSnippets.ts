export type SampleSnippet = {
  label: string;
  fileName: string;
  code: string;
};

export const sampleSnippets: SampleSnippet[] = [
  {
    label: 'NullPointerException risk',
    fileName: 'NullPointerDemo.java',
    code: `public class NullPointerDemo {
    public String readName(User user) {
        String name = user == null ? null : user.getName();
        return name.trim();
    }
}

class User {
    private final String name;

    public User(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}`
  },
  {
    label: 'SQL injection',
    fileName: 'SqlInjectionDemo.java',
    code: `import java.sql.*;

public class SqlInjectionDemo {
    public ResultSet loadUser(String username, Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        return statement.executeQuery("SELECT * FROM users WHERE username = '" + username + "'");
    }
}`
  },
  {
    label: 'Resource leak',
    fileName: 'ResourceLeakDemo.java',
    code: `import java.io.*;

public class ResourceLeakDemo {
    public byte[] readBytes(String path) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(path);
        byte[] content = fileInputStream.readAllBytes();
        return content;
    }
}`
  },
  {
    label: 'Hardcoded credentials',
    fileName: 'CredentialsDemo.java',
    code: `public class CredentialsDemo {
    public boolean login() {
        String password = "admin123";
        String secret = "super-secret";
        return password.equals(secret);
    }
}`
  },
  {
    label: 'equals/hashCode contract issue',
    fileName: 'EqualsHashcodeDemo.java',
    code: `import java.util.Objects;

public class EqualsHashcodeDemo {
    private final String id;

    public EqualsHashcodeDemo(String id) { this.id = id; }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        EqualsHashcodeDemo that = (EqualsHashcodeDemo) obj;
        return Objects.equals(id, that.id);
    }
}`
  },
  {
    label: 'Swallowed exception',
    fileName: 'SwallowedExceptionDemo.java',
    code: `import java.io.*;

public class SwallowedExceptionDemo {
    public boolean load(String path) {
        try {
            FileInputStream in = new FileInputStream(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}`
  }
];
