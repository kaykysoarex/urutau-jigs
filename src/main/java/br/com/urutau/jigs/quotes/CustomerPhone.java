
package br.com.urutau.jigs.quotes;

final class CustomerPhone {
    private static final String NATIONAL = "[1-9][0-9](?:[2-5][0-9]{7}|9[0-9]{8})";

    private CustomerPhone() {}

    static String normalize(String phone) {
        if (phone == null || !phone.matches("\\+?[0-9().\\s-]+")) return null;
        String digits = phone.replaceAll("\\D", "");
        boolean international = phone.startsWith("+") || digits.startsWith("00");
        if (!phone.startsWith("+") && digits.startsWith("00")) digits = digits.substring(2);
        if (!international && digits.matches(NATIONAL)) return "55" + digits;
        if (digits.matches("55" + NATIONAL)) return digits;
        if (international && !digits.startsWith("55") && digits.matches("[1-9][0-9]{7,14}")) return digits;
        return null;
    }
}
