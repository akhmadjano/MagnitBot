package org.example;

public enum BotState {
    // Umumiy holat
    START,

    // Admin login
    ADMIN_WAIT_USERNAME,
    ADMIN_WAIT_PASSWORD,

    // Admin menu
    ADMIN_MENU,
    ADMIN_UPLOAD_TEST,
    ADMIN_WAIT_RESULT_ID,

    // User menu
    USER_MENU,
    USER_WAIT_TEST_ID,
    USER_WAIT_ANSWERS
}