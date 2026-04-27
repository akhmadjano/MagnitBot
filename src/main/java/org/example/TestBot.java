package org.example;

import org.example.DatabaseManager.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.cdimascio.dotenv.Dotenv;

public class TestBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final DatabaseManager db = DatabaseManager.getInstance();

    // Har bir user uchun holat
    private final Map<Long, BotState>  userStates   = new ConcurrentHashMap<>();
    // Vaqtinchalik ma'lumotlar (login uchun username, test topshirish uchun testId)
    private final Map<Long, String>    tempData      = new ConcurrentHashMap<>();
    // Admin ekanini belgilash
    private final Map<Long, Boolean>   isAdmin       = new ConcurrentHashMap<>();
    private final Map<Long, String>    adminUsername = new ConcurrentHashMap<>();

    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    // Tugma matnlari
    private static final String BTN_UPLOAD   = "📤 Test yuklash";
    private static final String BTN_RESULT   = "📊 Natija";
    private static final String BTN_ALL      = "📋 Barcha testlar";
    private static final String BTN_LOGOUT   = "🚪 Chiqish";
    private static final String BTN_SUBMIT   = "📝 Vazifa topshirish";
    private static final String BTN_MY_RES   = "🏆 So'ngi natijalarim";

    public TestBot() {
    super("8312063746:AAFu-XVsqxvxML6RXUxElnsq3eCt1Lm3L4s"); // BOT_TOKEN
    this.botUsername = "Magnit_checker_bot";
}

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    // ═══════════════════════════════════════════════════════
    // ASOSIY HANDLER
    // ═══════════════════════════════════════════════════════
    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();
        User   tgUser = update.getMessage().getFrom();

        BotState state = userStates.getOrDefault(chatId, BotState.START);

        // ── /start ─────────────────────────────────────────
        if (text.equals("/start")) {
            handleStart(chatId, tgUser);
            return;
        }

        // ── /entertoadmin ──────────────────────────────────
        if (text.equals("/entertoadmin")) {
            handleEnterAdmin(chatId);
            return;
        }

        // ── State machine ──────────────────────────────────
        switch (state) {
            case ADMIN_WAIT_USERNAME -> handleAdminUsername(chatId, text);
            case ADMIN_WAIT_PASSWORD -> handleAdminPassword(chatId, text, tgUser);
            case ADMIN_MENU          -> handleAdminMenu(chatId, text, tgUser);
            case ADMIN_UPLOAD_TEST   -> handleUploadTest(chatId, text, tgUser);
            case ADMIN_WAIT_RESULT_ID-> handleResultId(chatId, text);
            case USER_MENU           -> handleUserMenu(chatId, text);
            case USER_WAIT_TEST_ID   -> handleUserTestId(chatId, text, tgUser);
            case USER_WAIT_ANSWERS   -> handleUserAnswers(chatId, text, tgUser);
            default                  -> handleStart(chatId, tgUser);
        }
    }

    // ═══════════════════════════════════════════════════════
    // /start
    // ═══════════════════════════════════════════════════════
    private void handleStart(long chatId, User user) {
        if (isAdmin.getOrDefault(chatId, false)) {
            sendMsg(chatId,
                    "👨\u200D💼 Xush kelibsiz, *" + adminUsername.getOrDefault(chatId, "Admin") + "*!\n" +
                            "Admin paneldasiz.",
                    adminKeyboard());
            setState(chatId, BotState.ADMIN_MENU);
        } else {
            sendMsg(chatId,
                    "Salom, *" + escMd(user.getFirstName()) + "*! 👋\n" +
                            "Test botiga xush kelibsiz!\n\n" +
                            "Pastdagi tugmalardan birini tanlang 👇",
                    userKeyboard());
            setState(chatId, BotState.USER_MENU);
        }
    }

    // ═══════════════════════════════════════════════════════
    // ADMIN LOGIN
    // ═══════════════════════════════════════════════════════
    private void handleEnterAdmin(long chatId) {
        sendMsg(chatId,
                "👨\u200D💼 *Admin paneliga kirish*\n\nFoydalanuvchi nomini kiriting:",
                removeKeyboard());
        setState(chatId, BotState.ADMIN_WAIT_USERNAME);
    }

    private void handleAdminUsername(long chatId, String text) {
        tempData.put(chatId, text);
        sendMsg(chatId, "🔐 Parolni kiriting:", null);
        setState(chatId, BotState.ADMIN_WAIT_PASSWORD);
    }

    private void handleAdminPassword(long chatId, String password, User user) {
        String uname = tempData.remove(chatId);
        if (db.checkAdmin(uname, password)) {
            isAdmin.put(chatId, true);
            adminUsername.put(chatId, uname);
            sendMsg(chatId,
                    "✅ Xush kelibsiz, *" + escMd(uname) + "*!\nAdmin paneldasiz. 👨\u200D💼",
                    adminKeyboard());
            setState(chatId, BotState.ADMIN_MENU);
        } else {
            sendMsg(chatId,
                    "❌ Login yoki parol noto'g'ri\\!\n\n" +
                            "Qayta urinish uchun: /entertoadmin", null);
            setState(chatId, BotState.START);
        }
    }

    // ═══════════════════════════════════════════════════════
    // ADMIN MENU
    // ═══════════════════════════════════════════════════════
    private void handleAdminMenu(long chatId, String text, User user) {
        switch (text) {
            case BTN_UPLOAD -> {
                sendMsg(chatId,
                        "📝 *Test yuklash*\n\n" +
                                "Javoblarni quyidagi formatda kiriting:\n\n" +
                                "`1.a -20`\n`2.b -10`\n`3.c -20`\n`4.-21 -15`\n\n" +
                                "_raqam\\.javob \\-ayiriladigan\\_ball_\n\n" +
                                "⬇️ Hamma savollarni bir xabarda yuboring:",
                        removeKeyboard());
                setState(chatId, BotState.ADMIN_UPLOAD_TEST);
            }
            case BTN_RESULT -> {
                sendMsg(chatId,
                        "🔢 Natijasini ko'rmoqchi bo'lgan *Test ID* sini kiriting:",
                        removeKeyboard());
                setState(chatId, BotState.ADMIN_WAIT_RESULT_ID);
            }
            case BTN_ALL    -> { showAllTests(chatId); }
            case BTN_LOGOUT -> {
                isAdmin.put(chatId, false);
                adminUsername.remove(chatId);
                sendMsg(chatId, "✅ Admin paneldan chiqdingiz\\.", userKeyboard());
                setState(chatId, BotState.USER_MENU);
            }
            default -> sendMsg(chatId, "Tugmani bosing 👇", adminKeyboard());
        }
    }

    // ───────────────────────────────────────────────────────
    // Test yuklash
    // ───────────────────────────────────────────────────────
    private void handleUploadTest(long chatId, String text, User user) {
        List<QuestionData> questions;
        try {
            questions = parseAdminAnswers(text);
        } catch (IllegalArgumentException e) {
            sendMsg(chatId,
                    "❌ Format xatosi:\n`" + escMd(e.getMessage()) + "`\n\n" +
                            "To'g'ri format:\n`1\\.a \\-20`\n`2\\.b \\-10`\n\nQaytadan kiriting:", null);
            return;
        }

        if (questions.isEmpty()) {
            sendMsg(chatId, "❌ Savollar topilmadi\\! Qaytadan kiriting:", null);
            return;
        }

        try {
            int testId = db.saveTest(adminUsername.getOrDefault(chatId, "admin"), questions);
            sendMsg(chatId,
                    "✅ *Test muvaffaqiyatli yuklandi\\!*\n\n" +
                            "┌─────────────────────\n" +
                            "│ 📌 Test ID : `" + testId + "`\n" +
                            "│ 📊 Savollar: " + questions.size() + " ta\n" +
                            "│ 🟢 Holat    : Tayyor\n" +
                            "└─────────────────────\n\n" +
                            "Bu ID ni foydalanuvchilarga yuboring\\!",
                    adminKeyboard());
            setState(chatId, BotState.ADMIN_MENU);
        } catch (Exception e) {
            sendMsg(chatId, "❌ Xatolik yuz berdi: " + escMd(e.getMessage()), adminKeyboard());
            setState(chatId, BotState.ADMIN_MENU);
        }
    }

    // ───────────────────────────────────────────────────────
    // Natija (admin)
    // ───────────────────────────────────────────────────────
    private void handleResultId(long chatId, String text) {
        if (!text.matches("\\d+")) {
            sendMsg(chatId, "❌ Test ID butun son bo'lishi kerak\\. Qaytadan kiriting:", null);
            return;
        }

        int testId = Integer.parseInt(text);
        TestInfo testInfo = db.getTestInfo(testId);

        if (testInfo == null) {
            sendMsg(chatId,
                    "❌ *" + testId + "* ID li test topilmadi\\!\nQaytadan kiriting:", null);
            return;
        }

        List<UserResultRow> results = db.getTestResults(testId);
        String dateStr = SDF.format(testInfo.createdAt());

        if (results.isEmpty()) {
            sendMsg(chatId,
                    "📊 *Test \\#" + testId + "*\n" +
                            "📅 Yaratilgan: " + escMd(dateStr) + "\n\n" +
                            "Hali hech kim bu testni topshirmagan\\.",
                    adminKeyboard());
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("📊 *Test \\#").append(testId).append(" natijalari*\n");
            sb.append("📅 ").append(escMd(dateStr))
                    .append(" \\| ").append(testInfo.totalQuestions()).append(" savol\n");
            sb.append("👥 Qatnashchilar: ").append(results.size()).append(" ta\n");
            sb.append("─────────────────────────\n");

            int i = 1;
            for (UserResultRow r : results) {
                String name = (r.fullName() != null && !r.fullName().isBlank())
                        ? r.fullName()
                        : (r.username() != null ? "@" + r.username() : "User " + r.userId());
                sb.append(i++).append("\\. ").append(escMd(name)).append("\n");
                sb.append("   🏆 ").append(r.score()).append(" ball  ");
                sb.append("✅").append(r.correctCnt());
                sb.append(" ❌").append(r.wrongCnt());
                sb.append(" ⭕").append(r.emptyCnt()).append("\n");
            }

            sendMsg(chatId, sb.toString(), adminKeyboard());
        }
        setState(chatId, BotState.ADMIN_MENU);
    }

    // ───────────────────────────────────────────────────────
    // Barcha testlar
    // ───────────────────────────────────────────────────────
    private void showAllTests(long chatId) {
        List<AllTestRow> tests = db.getAllTests();

        if (tests.isEmpty()) {
            sendMsg(chatId, "📋 Hali testlar yo'q\\.", adminKeyboard());
            return;
        }

        StringBuilder sb = new StringBuilder("📋 *Barcha testlar:*\n");
        sb.append("─────────────────────────\n");
        for (AllTestRow t : tests) {
            sb.append("📌 ID: `").append(t.id()).append("`\n");
            sb.append("   📅 ").append(escMd(SDF.format(t.createdAt()))).append("\n");
            sb.append("   📊 ").append(t.totalQuestions()).append(" savol");
            sb.append(" \\| 👥 ").append(t.submissions()).append(" topshirish\n");
        }
        sendMsg(chatId, sb.toString(), adminKeyboard());
    }

    // ═══════════════════════════════════════════════════════
    // USER MENU
    // ═══════════════════════════════════════════════════════
    private void handleUserMenu(long chatId, String text) {
        switch (text) {
            case BTN_SUBMIT -> {
                sendMsg(chatId, "🔢 Test ID sini kiriting:", removeKeyboard());
                setState(chatId, BotState.USER_WAIT_TEST_ID);
            }
            case BTN_MY_RES -> showMyResults(chatId);
            default         -> sendMsg(chatId, "Tugmani bosing 👇", userKeyboard());
        }
    }

    // ───────────────────────────────────────────────────────
    // Test ID kiritish
    // ───────────────────────────────────────────────────────
    private void handleUserTestId(long chatId, String text, User user) {
        if (!text.matches("\\d+")) {
            sendMsg(chatId, "❌ Test ID butun son bo'lishi kerak\\. Qaytadan kiriting:", null);
            return;
        }

        int testId = Integer.parseInt(text);
        TestInfo testInfo = db.getTestInfo(testId);

        if (testInfo == null) {
            sendMsg(chatId,
                    "❌ *" + testId + "* ID li test topilmadi\\!\nQaytadan kiriting:", null);
            return;
        }

        if (db.hasAlreadySubmitted(user.getId(), testId)) {
            sendMsg(chatId,
                    "⚠️ Siz *Test \\#" + testId + "* ni allaqachon topshirgansiz\\!\n" +
                            "Bir testni faqat bir marta topshirish mumkin\\.",
                    userKeyboard());
            setState(chatId, BotState.USER_MENU);
            return;
        }

        tempData.put(chatId, String.valueOf(testId));
        sendMsg(chatId,
                "✅ *Test \\#" + testId + "* topildi\\!  \\(" + testInfo.totalQuestions() + " ta savol\\)\n\n" +
                        "📝 Javoblaringizni quyidagi formatda kiriting:\n\n" +
                        "`1\\.a`\n`2\\.b`\n`3\\.c`\n`4\\.\\-21`\n\n" +
                        "⬇️ Hamma javoblarni bir xabarda yuboring:", null);
        setState(chatId, BotState.USER_WAIT_ANSWERS);
    }

    // ───────────────────────────────────────────────────────
    // Javoblarni qabul qilish va hisoblash
    // ───────────────────────────────────────────────────────
    private void handleUserAnswers(long chatId, String text, User user) {
        int testId = Integer.parseInt(tempData.getOrDefault(chatId, "0"));

        Map<Integer, String> userAnswers;
        try {
            userAnswers = parseUserAnswers(text);
        } catch (IllegalArgumentException e) {
            sendMsg(chatId,
                    "❌ Format xatosi:\n`" + escMd(e.getMessage()) + "`\n\n" +
                            "To'g'ri format:\n`1\\.a`\n`2\\.b`\n\nQaytadan kiriting:", null);
            return;
        }

        if (userAnswers.isEmpty()) {
            sendMsg(chatId, "❌ Javoblar topilmadi\\! Qaytadan kiriting:", null);
            return;
        }

        Map<Integer, QuestionData> correctAnswers = db.getCorrectAnswers(testId);
        if (correctAnswers.isEmpty()) {
            sendMsg(chatId, "❌ Test topilmadi\\.", userKeyboard());
            setState(chatId, BotState.USER_MENU);
            return;
        }

        // ── Ball hisoblash ─────────────────────────
        int score      = 800;
        int correctCnt = 0;
        int wrongCnt   = 0;
        int emptyCnt   = 0;
        List<AnswerDetail> details = new ArrayList<>();

        // ✅ Hech qanday javob yo'q bo'lsa → 200 ball
        boolean allEmpty = userAnswers.values().stream().allMatch(Objects::isNull);
        if (userAnswers.size() < correctAnswers.size() && userAnswers.isEmpty()) {
            score = 200;
        }

        for (Map.Entry<Integer, QuestionData> entry : correctAnswers.entrySet()) {
            int qNum           = entry.getKey();
            QuestionData qData = entry.getValue();
            String userAns     = userAnswers.get(qNum);

            if (userAns == null) {
                emptyCnt++;
                score -= qData.penalty();
                details.add(new AnswerDetail(qNum, null, false, qData.penalty()));
            } else if (userAns.equalsIgnoreCase(qData.answer())) {
                correctCnt++;
                details.add(new AnswerDetail(qNum, userAns, true, 0));
            } else {
                wrongCnt++;
                score -= qData.penalty();
                details.add(new AnswerDetail(qNum, userAns, false, qData.penalty()));
            }
        }

        // 🔻 Minimum 200 (hammasi noto'g'ri bo'lsa ham)
        score = Math.max(200, score);
        // ── DB ga saqlash ──────────────────────────
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName  = user.getLastName()  != null ? user.getLastName()  : "";
        String fullName  = (firstName + " " + lastName).trim();

        try {
            db.saveUserResult(
                    user.getId(), user.getUserName(), fullName,
                    testId, score, correctCnt, wrongCnt, emptyCnt, details
            );
        } catch (Exception e) {
            sendMsg(chatId, "❌ Saqlashda xatolik: " + escMd(e.getMessage()), userKeyboard());
            setState(chatId, BotState.USER_MENU);
            return;
        }

        int total = correctAnswers.size();
        sendMsg(chatId,
                "🎉 *Natijangiz:*\n\n" +
                        "┌──────────────────────\n" +
                        "│ 📌 Test ID  : " + testId + "\n" +
                        "│ ✅ To'g'ri  : " + correctCnt + "/" + total + "\n" +
                        "│ ❌ Noto'g'ri: " + wrongCnt + "\n" +
                        "│ ⭕ Javobsiz : " + emptyCnt + "\n" +
                        "├──────────────────────\n" +
                        "│ 🏆 Ball     : *" + score + "/800*\n" +
                        "└──────────────────────",
                userKeyboard());

        tempData.remove(chatId);
        setState(chatId, BotState.USER_MENU);
    }

    // ───────────────────────────────────────────────────────
    // So'ngi natijalar
    // ───────────────────────────────────────────────────────
    private void showMyResults(long chatId) {
        // chatId = userId (private chat)
        List<UserResultRow> results = db.getMyResults(chatId);

        if (results.isEmpty()) {
            sendMsg(chatId, "📊 Hali hech qanday test topshirmagansiz\\.", userKeyboard());
            return;
        }

        StringBuilder sb = new StringBuilder("🏆 *So'ngi natijalaringiz:*\n");
        sb.append("─────────────────────────\n");
        for (UserResultRow r : results) {
            sb.append("📌 Test \\#").append(r.testId()).append("  \\|  ");
            sb.append(escMd(SDF.format(r.submittedAt()))).append("\n");
            sb.append("   🏆 ").append(r.score()).append(" ball  ");
            sb.append("✅").append(r.correctCnt());
            sb.append(" ❌").append(r.wrongCnt());
            sb.append(" ⭕").append(r.emptyCnt()).append("\n");
        }
        sendMsg(chatId, sb.toString(), userKeyboard());
    }

    // ═══════════════════════════════════════════════════════
    // PARSING
    // ═══════════════════════════════════════════════════════

    /**
     * Admin format: "1.a -20"  yoki  "3.-21 -15"
     */
    private List<QuestionData> parseAdminAnswers(String text) {
        List<QuestionData> list = new ArrayList<>();
        Pattern p = Pattern.compile("^(\\d+)\\.([\\w\\-]+)\\s+(-?\\d+)$");
        for (String line : text.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = p.matcher(line);
            if (!m.matches()) throw new IllegalArgumentException(line);
            int    qNum    = Integer.parseInt(m.group(1));
            String answer  = m.group(2).toLowerCase();
            int    penalty = Math.abs(Integer.parseInt(m.group(3)));
            list.add(new QuestionData(qNum, answer, penalty));
        }
        return list;
    }

    /**
     * User format: "1.a"  yoki  "3.-21"
     */
    private Map<Integer, String> parseUserAnswers(String text) {
        Map<Integer, String> map = new LinkedHashMap<>();
        Pattern p = Pattern.compile("^(\\d+)\\.([\\w\\-]+)$");
        for (String line : text.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = p.matcher(line);
            if (!m.matches()) throw new IllegalArgumentException(line);
            map.put(Integer.parseInt(m.group(1)), m.group(2).toLowerCase());
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════
    // KEYBOARD va XABAR YUBORISH
    // ═══════════════════════════════════════════════════════
    private ReplyKeyboardMarkup adminKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(BTN_UPLOAD));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(BTN_RESULT));
        row2.add(new KeyboardButton(BTN_ALL));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(BTN_LOGOUT));

        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(List.of(row1, row2, row3));
        kb.setResizeKeyboard(true);
        return kb;
    }

    private ReplyKeyboardMarkup userKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(BTN_SUBMIT));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(BTN_MY_RES));

        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(List.of(row1, row2));
        kb.setResizeKeyboard(true);
        return kb;
    }

    private ReplyKeyboardRemove removeKeyboard() {
        return new ReplyKeyboardRemove(true);
    }

    private void sendMsg(long chatId, String text,
                         org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard keyboard) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.setParseMode("MarkdownV2");
        if (keyboard != null) msg.setReplyMarkup(keyboard);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            // MarkdownV2 escape xatosi bo'lsa oddiy matn sifatida yuborish
            try {
                SendMessage fallback = new SendMessage();
                fallback.setChatId(chatId);
                fallback.setText(text.replaceAll("[*_`\\[\\]()~>#+=|{}.!\\\\\\-]", ""));
                if (keyboard != null) fallback.setReplyMarkup(keyboard);
                execute(fallback);
            } catch (TelegramApiException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void setState(long chatId, BotState state) {
        userStates.put(chatId, state);
    }

    /** MarkdownV2 uchun maxsus belgilarni escape qilish */
    private String escMd(String s) {
        if (s == null) return "";
        return s.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!\\\\])", "\\\\$1");
    }
}
