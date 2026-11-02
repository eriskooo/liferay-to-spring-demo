package com.example.ssomock;

import com.example.ssomock.Directory.Customer;
import com.example.ssomock.Directory.Operator;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * Prihlásenie, zmena zákazníka a odhlásenie. Stav je len v {@link PortalSessionStore} a v cookie {@code SSO_ID};
 * {@code HttpSession} sa nepoužíva (kolízia JSESSIONID s Liferay za jednou adresou).
 */
@Controller
public class LoginController {

    static final String COOKIE = "SSO_ID";
    static final String DEFAULT_RETURN_URL = "/web/crm/blacklist";

    private final Directory directory;
    private final PortalSessionStore sessions;
    private final LoginPage page;

    public LoginController(Directory directory, PortalSessionStore sessions, LoginPage page) {
        this.directory = directory;
        this.sessions = sessions;
        this.page = page;
    }

    /** Prihlasovacia stránka. */
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String loginPage(@RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl,
                            @CookieValue(name = COOKIE, required = false) String sessionId) {
        return page.render(safeReturnUrl(returnUrl), sessions.find(sessionId));
    }

    /** Prihlásenie operátora so zákazníkom. */
    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestParam String operator, @RequestParam String customer,
                                      @RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl) {
        Operator op = directory.findOperator(operator)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "neznámý operátor"));
        Customer cu = requireCustomer(customer);
        String id = sessions.create(op, cu);
        return redirect(safeReturnUrl(returnUrl), cookie(id, Duration.ofHours(8)));
    }

    /** Zmena zákazníka v existujúcej session. */
    @PostMapping("/customer")
    public ResponseEntity<Void> changeCustomer(@RequestParam String customer,
                                               @RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl,
                                               @CookieValue(name = COOKIE, required = false) String sessionId) {
        String target = safeReturnUrl(returnUrl);
        if (sessions.find(sessionId).isEmpty()) {
            return redirect("/sso/login?returnUrl=" + URLEncoder.encode(target, StandardCharsets.UTF_8), null);
        }
        sessions.changeCustomer(sessionId, requireCustomer(customer));
        return redirect(target, null);
    }

    /** Odhlásenie – zruší session a cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String sessionId) {
        sessions.remove(sessionId);
        return redirect("/sso/login", cookie("", Duration.ZERO));
    }

    /** Len relatívna cesta v rámci tej istej adresy – inak by mock slúžil ako open redirect. */
    static String safeReturnUrl(String url) {
        if (url == null || !url.startsWith("/") || url.startsWith("//") || url.contains("\\")) {
            return DEFAULT_RETURN_URL;
        }
        return url;
    }

    private Customer requireCustomer(String customerId) {
        return directory.findCustomer(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "neznámý zákazník"));
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        // Path=/ – cookie musí vidieť Liferay aj (v podprojekte 2) nová appka za tým istým nginx.
        return ResponseCookie.from(COOKIE, value).path("/").httpOnly(true).sameSite("Lax").maxAge(maxAge).build();
    }

    private static ResponseEntity<Void> redirect(String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location));
        if (cookie != null) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }
}
