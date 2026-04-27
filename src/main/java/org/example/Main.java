package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        // Database jadvallarini yaratish
        DatabaseManager db = DatabaseManager.getInstance();
        db.initDatabase();

        // Botni ishga tushirish
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new TestBot());
            System.out.println("🤖 Bot muvaffaqiyatli ishga tushdi!");
        } catch (TelegramApiException e) {
            System.err.println("❌ Bot ishga tushishda xatolik: " + e.getMessage());
            e.printStackTrace();
        }
    }
}