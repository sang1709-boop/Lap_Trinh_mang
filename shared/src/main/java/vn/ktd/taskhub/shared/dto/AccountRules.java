package vn.ktd.taskhub.shared.dto;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.Locale;

/** Rules used by both the desktop forms and server-side validation. */
public final class AccountRules {
    private AccountRules() {}
    public static boolean student(String s){return s.matches("[A-Za-z0-9]{6,12}");}
    public static boolean email(String s){return s.length()<=254 && s.matches("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+");}
    public static LocalDate birthDate(String s){
        try {
            var date=LocalDate.parse(s,DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));
            if(date.getYear()>=1000 && !date.isAfter(LocalDate.now().minusYears(16)))return date;
        }catch(DateTimeException ignored){}
        throw new IllegalArgumentException("BAD_BIRTH_DATE");
    }
    public static boolean password(String value,String student) {
        int size=value.codePointCount(0,value.length());
        return size>=8 && size<=32 && value.getBytes(StandardCharsets.UTF_8).length<=72
                && (student.isEmpty() || !value.toLowerCase(Locale.ROOT).contains(student.toLowerCase(Locale.ROOT)))
                && value.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c))
                && value.codePoints().anyMatch(Character::isUpperCase)
                && value.codePoints().anyMatch(Character::isLowerCase)
                && value.codePoints().anyMatch(Character::isDigit)
                && value.codePoints().anyMatch(c->!Character.isLetterOrDigit(c));
    }
}
