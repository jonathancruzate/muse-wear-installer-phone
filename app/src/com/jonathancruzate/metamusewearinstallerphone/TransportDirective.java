package com.jonathancruzate.metamusewearinstallerphone;

/**
 * Transport-directive decision logic — the pure-Java half of
 * WorkerClient.handleTransportDirective (the network probe and the
 * Store writes live there; every decision is made here so the plain-
 * JVM suite in tools/TransportDirectiveTest can pin the semantics).
 *
 * A directive is the Worker's {"transport": {"base", "secret"}}
 * object, the per-device analogue of Device Assistance v1.9's
 * mode-state transport directive: the Worker hands the app a new
 * base + secret, the app PROBES the new base with the new secret
 * before switching, and persists the pair only on probe success.
 * Semantics ported from DA's handleTransportDirective:
 *   - absent directive            -> the caller never invokes decide
 *   - malformed directive         -> INVALID (ignored, stay)
 *   - directive == current pair   -> SAME (no-op)
 *   - directive != current pair   -> NEEDS_PROBE, then SWITCH on
 *     probe success or STAY_PROBE_FAILED on probe failure (the old
 *     pair stays in force either way until SWITCH is persisted).
 * A same-base / new-secret directive is a secret rotation: it is
 * NOT "same" unless the secret also matches, and it goes through
 * the same probe-then-switch path.
 *
 * No Android imports, no I/O, no logging — and this class never
 * sees a secret value it would print: decisions only, values are
 * compared, never rendered.
 */
public final class TransportDirective {
    private TransportDirective() {}

    public enum Decision {
        /** Directive present but malformed — ignore it, stay. */
        INVALID,
        /** Directive already equals the current base + secret. */
        SAME,
        /** Directive is well-formed and new — probe before switching. */
        NEEDS_PROBE,
        /** Probe succeeded — persist the new pair. */
        SWITCH,
        /** Probe failed — keep the current pair. */
        STAY_PROBE_FAILED
    }

    /**
     * Well-formed = a base the app may switch to and a secret long
     * enough to be a real credential. Same rule as DA's: https
     * anywhere, or http only on the tailnet 100.64/10 relay range.
     */
    public static boolean isWellFormed(String base, String secret) {
        if (base == null || secret == null) return false;
        String b = base.trim();
        if (b.isEmpty()) return false;
        if (!(b.startsWith("https://") || b.startsWith("http://100."))) return false;
        if (b.indexOf(' ') >= 0 || b.indexOf('\n') >= 0 || b.indexOf('\r') >= 0) return false;
        if (secret.trim().length() < 16) return false;
        return true;
    }

    /**
     * Decide what to do with a directive that IS present.
     *
     * @param probeOk null when the probe has not run yet; TRUE/FALSE
     *                once it has. Only consulted when the directive
     *                is well-formed and differs from the current pair.
     */
    public static Decision decide(String currentBase, String currentSecret,
                                  String newBase, String newSecret,
                                  Boolean probeOk) {
        if (!isWellFormed(newBase, newSecret)) return Decision.INVALID;
        String nb = newBase.trim();
        String ns = newSecret.trim();
        String cb = currentBase == null ? "" : currentBase.trim();
        String cs = currentSecret == null ? "" : currentSecret.trim();
        if (nb.equals(cb) && ns.equals(cs)) return Decision.SAME;
        if (probeOk == null) return Decision.NEEDS_PROBE;
        return probeOk ? Decision.SWITCH : Decision.STAY_PROBE_FAILED;
    }
}
