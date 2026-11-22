import com.jonathancruzate.metamusewearinstallerphone.WearGate;

import java.util.List;

/**
 * WearGateTest — plain-JVM assertions for the standalone phone
 * app's command gate (same discipline as the fleet's other tool
 * tests: no JUnit, run with javac/java, PASS/FAIL per case,
 * ALL_PASS + exit code carry the verdict).
 *
 *   javac -cp ../app/src -d /tmp/wgt . ; java -cp /tmp/wgt:. WearGateTest
 *
 * The wear_install_face cases are ported from Muse Device
 * Assistance's tools/ShellAllowlistTest (the v1.13 block), with the
 * same job/refusals; the standalone-only cases pin that this app
 * accepts NOTHING else.
 */
public class WearGateTest {
    static int pass = 0, fail = 0;

    static void expect(boolean cond, String name) {
        if (cond) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) {
        // ---- wear_install_face (dedicated validator) ----
        // Its slots are typed values (label / https URL / sha256),
        // never shell text, so the URL legitimately carries
        // characters shared shell hygiene bans ('?', '&', '=').
        expect(WearGate.isWearTemplate("wear_install_face"),
                "wear_install_face is the wear template");
        expect(!WearGate.isWearTemplate("battery"),
                "battery is not a wear template");
        String wSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        WearGate.Gate wOk = WearGate.check("wear_install_face",
                List.of("swiss-army-v6.2", "https://muse.example.com/faces/swiss.apk?sig=abc&x=1", wSha));
        expect(wOk.allowed && "wear_install".equals(wOk.op)
                && wOk.args != null && wOk.args.length == 3
                && "swiss-army-v6.2".equals(wOk.args[0])
                && wSha.equals(wOk.args[2]), "wear_install_face valid job allowed (op+args)");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a.apk", "xyz")).allowed,
                "wear_install_face bad sha refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a.apk", wSha.toUpperCase())).allowed,
                "wear_install_face uppercase sha refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "http://h.example/a.apk", wSha)).allowed,
                "wear_install_face http refused");
        expect(!WearGate.check("wear_install_face",
                List.of("Swiss Army", "https://h.example/a.apk", wSha)).allowed,
                "wear_install_face label with space refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a.apk")).allowed,
                "wear_install_face 2 args refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a.apk", wSha, "extra")).allowed,
                "wear_install_face 4 args refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://user@h.example/a.apk", wSha)).allowed,
                "wear_install_face userinfo refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a b.apk", wSha)).allowed,
                "wear_install_face url with space refused");
        expect(!WearGate.check("wear_install_face",
                List.of("label", "https://h.example/a.apk\";reboot", wSha)).allowed,
                "wear_install_face url injection refused");

        // ---- standalone-only: nothing else passes, ever ----
        expect(!WearGate.check("battery", List.of()).allowed,
                "shell template battery refused");
        expect(!WearGate.check("voice_bridge", List.of("hello")).allowed,
                "voice_bridge refused");
        expect(!WearGate.check("", List.of()).allowed,
                "empty template refused");
        expect(!WearGate.check(null, List.of()).allowed,
                "null template refused");

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? "ALL_PASS" : "FAILURES:" + fail);
        if (fail > 0) System.exit(1);
    }
}
