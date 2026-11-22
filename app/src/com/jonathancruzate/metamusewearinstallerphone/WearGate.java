package com.jonathancruzate.metamusewearinstallerphone;

import java.util.List;
import java.util.regex.Pattern;

/**
 * WearGate — the on-device gate for the wear-install command lane,
 * the standalone app's ShellAllowlist-equivalent (the validator is
 * ported from Muse Device Assistance's
 * ShellAllowlist.checkWearInstall, v1.13 — same rules, same refusal
 * strings).
 *
 * This app accepts exactly ONE template: `wear_install_face`
 * {label, https-url, sha256}. Everything else is refused. The
 * Worker is a transport only — it cannot widen this gate.
 *
 * The args are typed values, never shell text (nothing in this app
 * ever builds a shell command), so the validator re-implements the
 * shared hygiene protections per slot: https-only URL with a host,
 * no userinfo, no whitespace/quotes/control characters, a strict
 * label token charset, and the exact sha256 shape.
 *
 * Deliberately free of Android dependencies so the gate is
 * unit-testable on a plain JVM: tools/WearGateTest.
 */
public final class WearGate {
    private WearGate() {}

    public static final String TEMPLATE = "wear_install_face";
    public static final String OP = "wear_install";

    private static final Pattern WEAR_LABEL = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile(
            "^[0-9a-f]{64}$");

    private static boolean ok(Pattern p, String v) {
        return v != null && p.matcher(v).matches();
    }

    /** Result of a gate check: the validated args, or the refusal. */
    public static final class Gate {
        public final boolean allowed;
        public final String op;
        public final String refusal;
        /** Validated {label, url, sha256}; null when refused. */
        public final String[] args;

        Gate(boolean allowed, String op, String refusal, String[] args) {
            this.allowed = allowed;
            this.op = op;
            this.refusal = refusal;
            this.args = args;
        }
    }

    private static Gate refuse(String why) {
        return new Gate(false, null, why, null);
    }

    public static boolean isWearTemplate(String template) {
        return TEMPLATE.equals(template);
    }

    /** Gate one fetched command. Only wear_install_face can pass. */
    public static Gate check(String template, List<String> args) {
        if (!TEMPLATE.equals(template)) {
            return refuse("template not allowed in this app: " + template);
        }
        return checkWearInstall(args);
    }

    // Ported from ShellAllowlist.checkWearInstall (Device Assistance
    // v1.13) — keep the rules in lockstep with that copy.
    private static Gate checkWearInstall(List<String> args) {
        if (args.size() != 3) return refuse("needs <label> <url> <sha256>");
        String label = args.get(0);
        String url = args.get(1);
        String sha = args.get(2);
        if (!ok(WEAR_LABEL, label)) return refuse("bad label");
        if (!ok(SHA256_HEX, sha)) return refuse("bad sha256");
        if (url == null || url.length() < 10 || url.length() > 2048) {
            return refuse("bad url length");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F
                    || c == '"' || c == '\'' || c == '`' || c == '\\'
                    || c == '<' || c == '>' || c == '#') {
                return refuse("bad url character");
            }
        }
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (Exception e) {
            return refuse("unparseable url");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            return refuse("url must be https");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            return refuse("url has no host");
        }
        if (u.getUserInfo() != null) return refuse("url userinfo refused");
        return new Gate(true, OP, null, new String[]{label, url, sha});
    }
}
