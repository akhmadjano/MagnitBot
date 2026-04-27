package org.example;

import io.github.cdimascio.dotenv.Dotenv;

import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DatabaseManager {

    private static DatabaseManager instance;
    private final String url;
    private final String user;
    private final String password;

    // ───────────────────────────────────────────────
    // Singleton
    // ───────────────────────────────────────────────
   private DatabaseManager() {
    Dotenv env = Dotenv.configure()
            .ignoreIfMissing() // 🔥 shu qator muhim
            .load();

    String host = env.get("DB_HOST", "switchback.proxy.rlwy.net");
    String port = env.get("DB_PORT", "51822");
    String name = env.get("DB_NAME", "railway");

    this.user = env.get("DB_USER", "postgres");
    this.password = env.get("DB_PASSWORD", "SqKHCfSMJXAweUqgjQlSsaNYpUUbVwMW");

    this.url = "jdbc:postgresql://" + host + ":" + port + "/" + name;
}

    public static DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    // ───────────────────────────────────────────────
    // INIT — jadvallar yaratish
    // ───────────────────────────────────────────────
    public void initDatabase() {
        String[] sqls = {
                """
            CREATE TABLE IF NOT EXISTS admins (
                id            SERIAL PRIMARY KEY,
                username      VARCHAR(100) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                created_at    TIMESTAMP DEFAULT NOW()
            )
            """,
                """
            CREATE TABLE IF NOT EXISTS tests (
                id              SERIAL PRIMARY KEY,
                created_by      VARCHAR(100),
                created_at      TIMESTAMP DEFAULT NOW(),
                total_questions INT,
                is_active       BOOLEAN DEFAULT TRUE
            )
            """,
                """
            CREATE TABLE IF NOT EXISTS test_questions (
                id              SERIAL PRIMARY KEY,
                test_id         INT REFERENCES tests(id) ON DELETE CASCADE,
                question_number INT,
                correct_answer  VARCHAR(50),
                penalty         INT DEFAULT 0
            )
            """,
                """
            CREATE TABLE IF NOT EXISTS user_results (
                id           SERIAL PRIMARY KEY,
                user_id      BIGINT,
                username     VARCHAR(100),
                full_name    VARCHAR(200),
                test_id      INT REFERENCES tests(id) ON DELETE CASCADE,
                score        INT,
                correct_cnt  INT DEFAULT 0,
                wrong_cnt    INT DEFAULT 0,
                empty_cnt    INT DEFAULT 0,
                submitted_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(user_id, test_id)
            )
            """,
                """
            CREATE TABLE IF NOT EXISTS user_answer_details (
                id              SERIAL PRIMARY KEY,
                result_id       INT REFERENCES user_results(id) ON DELETE CASCADE,
                question_number INT,
                user_answer     VARCHAR(50),
                is_correct      BOOLEAN,
                penalty_applied INT DEFAULT 0
            )
            """
        };

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            for (String sql : sqls) st.execute(sql);

            // Default admin: login=admin, password=admin123
            String hash = sha256("admin123");
            st.execute(
                    "INSERT INTO admins (username, password_hash) VALUES ('admin', '" + hash + "') " +
                            "ON CONFLICT (username) DO NOTHING"
            );
            System.out.println("✅ Database initialized. Default admin: admin / admin123");
        } catch (Exception e) {
            throw new RuntimeException("DB init xatosi: " + e.getMessage(), e);
        }
    }

    // ───────────────────────────────────────────────
    // ADMIN
    // ───────────────────────────────────────────────
    public boolean checkAdmin(String username, String password) {
        String sql = "SELECT id FROM admins WHERE username = ? AND password_hash = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, sha256(password));
            return ps.executeQuery().next();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ───────────────────────────────────────────────
    // TEST YUKLASH
    // ───────────────────────────────────────────────
    public int saveTest(String adminUsername, List<QuestionData> questions) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // tests jadvaliga qo'shish
                int testId;
                String insertTest = "INSERT INTO tests (created_by, total_questions) VALUES (?, ?) RETURNING id";
                try (PreparedStatement ps = conn.prepareStatement(insertTest)) {
                    ps.setString(1, adminUsername);
                    ps.setInt(2, questions.size());
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    testId = rs.getInt(1);
                }

                // savollarni qo'shish
                String insertQ = "INSERT INTO test_questions (test_id, question_number, correct_answer, penalty) VALUES (?,?,?,?)";
                try (PreparedStatement ps = conn.prepareStatement(insertQ)) {
                    for (QuestionData q : questions) {
                        ps.setInt(1, testId);
                        ps.setInt(2, q.number());
                        ps.setString(3, q.answer());
                        ps.setInt(4, q.penalty());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
                return testId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ───────────────────────────────────────────────
    // NATIJA (admin uchun)
    // ───────────────────────────────────────────────
    public TestInfo getTestInfo(int testId) {
        String sql = "SELECT id, created_at, total_questions FROM tests WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, testId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new TestInfo(
                    rs.getInt("id"),
                    rs.getTimestamp("created_at"),
                    rs.getInt("total_questions")
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<UserResultRow> getTestResults(int testId) {
        String sql = """
            SELECT full_name, username, user_id, score,
                   correct_cnt, wrong_cnt, empty_cnt, submitted_at
            FROM user_results
            WHERE test_id = ?
            ORDER BY score DESC
        """;
        List<UserResultRow> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, testId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new UserResultRow(
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getLong("user_id"),
                        rs.getInt("score"),
                        rs.getInt("correct_cnt"),
                        rs.getInt("wrong_cnt"),
                        rs.getInt("empty_cnt"),
                        rs.getTimestamp("submitted_at")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // ───────────────────────────────────────────────
    // BARCHA TESTLAR
    // ───────────────────────────────────────────────
    public List<AllTestRow> getAllTests() {
        String sql = """
            SELECT t.id, t.created_at, t.total_questions, t.created_by,
                   COUNT(ur.id) AS submissions
            FROM tests t
            LEFT JOIN user_results ur ON ur.test_id = t.id
            GROUP BY t.id
            ORDER BY t.created_at DESC
        """;
        List<AllTestRow> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AllTestRow(
                        rs.getInt("id"),
                        rs.getTimestamp("created_at"),
                        rs.getInt("total_questions"),
                        rs.getString("created_by"),
                        rs.getInt("submissions")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // ───────────────────────────────────────────────
    // USER — test topshirish
    // ───────────────────────────────────────────────
    public boolean hasAlreadySubmitted(long userId, int testId) {
        String sql = "SELECT id FROM user_results WHERE user_id = ? AND test_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, testId);
            return ps.executeQuery().next();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** To'g'ri javoblarni map shaklida qaytaradi: {savol_raqami -> QuestionData} */
    public Map<Integer, QuestionData> getCorrectAnswers(int testId) {
        String sql = "SELECT question_number, correct_answer, penalty FROM test_questions WHERE test_id = ? ORDER BY question_number";
        Map<Integer, QuestionData> map = new LinkedHashMap<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, testId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int num = rs.getInt("question_number");
                map.put(num, new QuestionData(num, rs.getString("correct_answer"), rs.getInt("penalty")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    public void saveUserResult(
            long userId, String username, String fullName,
            int testId, int score, int correctCnt, int wrongCnt, int emptyCnt,
            List<AnswerDetail> details) throws SQLException {

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int resultId;
                String insertResult = """
                    INSERT INTO user_results
                        (user_id, username, full_name, test_id, score, correct_cnt, wrong_cnt, empty_cnt)
                    VALUES (?,?,?,?,?,?,?,?) RETURNING id
                """;
                try (PreparedStatement ps = conn.prepareStatement(insertResult)) {
                    ps.setLong(1, userId);
                    ps.setString(2, username);
                    ps.setString(3, fullName);
                    ps.setInt(4, testId);
                    ps.setInt(5, score);
                    ps.setInt(6, correctCnt);
                    ps.setInt(7, wrongCnt);
                    ps.setInt(8, emptyCnt);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    resultId = rs.getInt(1);
                }

                String insertDetail = """
                    INSERT INTO user_answer_details
                        (result_id, question_number, user_answer, is_correct, penalty_applied)
                    VALUES (?,?,?,?,?)
                """;
                try (PreparedStatement ps = conn.prepareStatement(insertDetail)) {
                    for (AnswerDetail d : details) {
                        ps.setInt(1, resultId);
                        ps.setInt(2, d.questionNumber());
                        ps.setString(3, d.userAnswer());
                        ps.setBoolean(4, d.correct());
                        ps.setInt(5, d.penaltyApplied());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ───────────────────────────────────────────────
    // USER — so'ngi natijalar
    // ───────────────────────────────────────────────
    public List<UserResultRow> getMyResults(long userId) {
        String sql = """
            SELECT full_name, username, user_id, score,
                   correct_cnt, wrong_cnt, empty_cnt, submitted_at, test_id
            FROM user_results
            WHERE user_id = ?
            ORDER BY submitted_at DESC
            LIMIT 10
        """;
        List<UserResultRow> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new UserResultRow(
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getLong("user_id"),
                        rs.getInt("score"),
                        rs.getInt("correct_cnt"),
                        rs.getInt("wrong_cnt"),
                        rs.getInt("empty_cnt"),
                        rs.getTimestamp("submitted_at"),
                        rs.getInt("test_id")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // ───────────────────────────────────────────────
    // SHA-256 helper
    // ───────────────────────────────────────────────
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ───────────────────────────────────────────────
    // Record classlar (DTO)
    // ───────────────────────────────────────────────
    public record QuestionData(int number, String answer, int penalty) {}

    public record AnswerDetail(int questionNumber, String userAnswer,
                               boolean correct, int penaltyApplied) {}

    public record TestInfo(int id, Timestamp createdAt, int totalQuestions) {}

    public record UserResultRow(String fullName, String username, long userId,
                                int score, int correctCnt, int wrongCnt,
                                int emptyCnt, Timestamp submittedAt, int testId) {
        // testId siz constructor (admin uchun)
        public UserResultRow(String fullName, String username, long userId,
                             int score, int correctCnt, int wrongCnt,
                             int emptyCnt, Timestamp submittedAt) {
            this(fullName, username, userId, score, correctCnt, wrongCnt, emptyCnt, submittedAt, 0);
        }
    }

    public record AllTestRow(int id, Timestamp createdAt, int totalQuestions,
                             String createdBy, int submissions) {}
}
