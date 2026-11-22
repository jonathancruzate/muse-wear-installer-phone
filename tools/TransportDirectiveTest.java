import com.jonathancruzate.metamusewearinstallerphone.TransportDirective;
import com.jonathancruzate.metamusewearinstallerphone.TransportDirective.Decision;

/**
 * TransportDirectiveTest — plain-JVM assertions for the v1.0.2
 * transport-directive decision logic (the pure half of
 * WorkerClient.handleTransportDirective; the network probe and the
 * Store writes live in the client and are exercised on-device).
 * Same discipline as the fleet's other tool tests: no JUnit,
 * PASS/FAIL per case, ALL_PASS + exit code carry the verdict.
 *
 *   javac -cp ../app/src -d /tmp/tdt TransportDirectiveTest.java
 *   java  -cp /tmp/tdt:. TransportDirectiveTest
 *
 * Cases pin the DA v1.9 semantics the client relies on:
 *   - probe-success switch / probe-failure stay
 *   - malformed directive ignored (INVALID)
 *   - same-base secret rotation is NOT "same" — it probes + switches
 *   - directive equal to the current pair is a no-op (SAME)
 * Secrets here are synthetic fixtures, never real credentials.
 */
public class TransportDirectiveTest {
    static int pass = 0, fail = 0;

    static void expect(boolean cond, String name) {
        if (cond) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static final String OLD_BASE = "https://jonathancruzate.com/mode-sync";
    static final String NEW_BASE = "https://jonathancruzate.com/bridge";
    static final String OLD_SECRET = "old-secret-fixture-0123456789";
    static final String NEW_SECRET = "new-secret-fixture-0123456789";

    public static void main(String[] args) {
        // ---- well-formedness ----
        expect(TransportDirective.isWellFormed(NEW_BASE, NEW_SECRET),
                "https base + long secret is well-formed");
        expect(TransportDirective.isWellFormed("http://100.64.0.9:8797", NEW_SECRET),
                "tailnet http base is well-formed");
        expect(!TransportDirective.isWellFormed("http://example.com/x", NEW_SECRET),
                "plain http off-tailnet is malformed");
        expect(!TransportDirective.isWellFormed("", NEW_SECRET),
                "empty base is malformed");
        expect(!TransportDirective.isWellFormed(null, NEW_SECRET),
                "null base is malformed");
        expect(!TransportDirective.isWellFormed(NEW_BASE, null),
                "null secret is malformed");
        expect(!TransportDirective.isWellFormed(NEW_BASE, "short"),
                "short secret is malformed");
        expect(!TransportDirective.isWellFormed("https://exa mple.com", NEW_SECRET),
                "space in base is malformed");

        // ---- malformed directive -> INVALID (stay, ignore) ----
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, "", NEW_SECRET, null)
                        == Decision.INVALID,
                "empty-base directive is INVALID");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, NEW_BASE, "short", null)
                        == Decision.INVALID,
                "short-secret directive is INVALID");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, "http://example.com", NEW_SECRET, Boolean.TRUE)
                        == Decision.INVALID,
                "malformed stays INVALID even with a good probe");

        // ---- directive == current pair -> SAME (no-op) ----
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, OLD_BASE, OLD_SECRET, null)
                        == Decision.SAME,
                "identical pair is SAME");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, " " + OLD_BASE + " ", OLD_SECRET, null)
                        == Decision.SAME,
                "identical pair with padding is SAME");

        // ---- new pair -> NEEDS_PROBE, then probe decides ----
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, NEW_BASE, NEW_SECRET, null)
                        == Decision.NEEDS_PROBE,
                "new pair needs a probe first");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, NEW_BASE, NEW_SECRET, Boolean.TRUE)
                        == Decision.SWITCH,
                "probe success switches");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, NEW_BASE, NEW_SECRET, Boolean.FALSE)
                        == Decision.STAY_PROBE_FAILED,
                "probe failure stays on the current pair");

        // ---- same-base rotation: new secret only ----
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, OLD_BASE, NEW_SECRET, null)
                        == Decision.NEEDS_PROBE,
                "same base + new secret is a rotation, not SAME");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, OLD_BASE, NEW_SECRET, Boolean.TRUE)
                        == Decision.SWITCH,
                "rotation switches on probe success");
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, OLD_BASE, NEW_SECRET, Boolean.FALSE)
                        == Decision.STAY_PROBE_FAILED,
                "rotation stays on probe failure");

        // ---- new base, same secret: still a move, still probed ----
        expect(TransportDirective.decide(OLD_BASE, OLD_SECRET, NEW_BASE, OLD_SECRET, null)
                        == Decision.NEEDS_PROBE,
                "new base + same secret needs a probe");

        // ---- unconfigured app (empty current pair) ----
        expect(TransportDirective.decide("", "", NEW_BASE, NEW_SECRET, null)
                        == Decision.NEEDS_PROBE,
                "empty current pair treats a valid directive as new");
        expect(TransportDirective.decide(null, null, NEW_BASE, NEW_SECRET, Boolean.TRUE)
                        == Decision.SWITCH,
                "null current pair switches on probe success");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail == 0) System.out.println("ALL_PASS");
        System.exit(fail == 0 ? 0 : 1);
    }
}
