package com.orqix.ai.tasks;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Small text helpers of the AI task code. */
final class AiSkillsText {

    private AiSkillsText() {
    }

    static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    static List<String> array(Array array) throws SQLException {
        return array == null ? List.of() : List.copyOf(Arrays.asList((String[]) array.getArray()));
    }
}
