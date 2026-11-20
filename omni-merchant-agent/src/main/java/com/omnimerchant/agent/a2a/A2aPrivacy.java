package com.omnimerchant.agent.a2a;

final class A2aPrivacy {
    private A2aPrivacy() {}
    static String redact(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
                .replaceAll("\\b\\+?\\d[\\d\\s().-]{7,}\\b", "[phone]")
                .replaceAll("\\b(?:\\d[ -]*?){13,19}\\b", "[card]");
    }
}
