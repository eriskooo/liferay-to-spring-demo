package com.example.ssomock;

import com.example.ssomock.PortalSessionStore.PortalSession;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Jednoduchá HTML stránka bez šablónovacieho enginu – mock nemá byť ďalšia technológia na učenie. */
@Component
public class LoginPage {

    private final Directory directory;

    public LoginPage(Directory directory) {
        this.directory = directory;
    }

    /** Vykreslí login (a pri existujúcej session aj jej stav, zmenu zákazníka a odhlásenie). */
    public String render(String returnUrl, Optional<PortalSession> current) {
        String safeReturn = htmlEscape(returnUrl);
        String state = current.map(s -> """
                <section>
                  <p>Přihlášen: %s · zákazník: %s</p>
                  <form method="post" action="/sso/customer">
                    <input type="hidden" name="returnUrl" value="%s">
                    <select name="customer">%s</select>
                    <button type="submit">Změnit zákazníka</button>
                  </form>
                  <form method="post" action="/sso/logout"><button type="submit">Odhlásit</button></form>
                  <p><a href="%s">Zpět do CRM</a></p>
                </section>
                """.formatted(htmlEscape(s.operator().displayName()), htmlEscape(s.customer().displayName()),
                safeReturn, customerOptions(), safeReturn)).orElse("");
        return """
                <!DOCTYPE html>
                <html lang="cs"><head><meta charset="UTF-8"><title>SSO portál (mock)</title></head>
                <body>
                <h1>SSO portál (mock)</h1>
                %s
                <form method="post" action="/sso/login">
                  <input type="hidden" name="returnUrl" value="%s">
                  <label>Operátor <select name="operator">%s</select></label>
                  <label>Zákazník <select name="customer">%s</select></label>
                  <button type="submit">Přihlásit</button>
                </form>
                </body></html>
                """.formatted(state, safeReturn, operatorOptions(), customerOptions());
    }

    private String operatorOptions() {
        return directory.operators().stream()
                .map(o -> option(o.login(), o.displayName() + " (" + o.login() + ")"))
                .collect(Collectors.joining());
    }

    private String customerOptions() {
        return directory.customers().stream()
                .map(c -> option(c.customerId(), c.customerId() + " – " + c.displayName()))
                .collect(Collectors.joining());
    }

    private static String option(String value, String label) {
        return "<option value=\"" + htmlEscape(value) + "\">" + htmlEscape(label) + "</option>";
    }

    // S kódovaním UTF-8 escapuje len &, <, >, " a ' – diakritika ostane čitateľná (bez &aacute;).
    private static String htmlEscape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
