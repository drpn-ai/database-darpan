package darpan.database

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Guardrail validation for admin-authored extraction SELECTs. This is defense in depth, not the
 * primary fence: the connection is opened read-only and the deployment requirement is a
 * SELECT-only MySQL grant for the Darpan user.
 */
class SqlSelectValidator {
    private static final Set<String> DENIED_TOKENS = [
            "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "CALL", "EXECUTE", "HANDLER", "LOAD", "OUTFILE", "DUMPFILE",
            "LOCK", "UNLOCK", "SET", "USE", "KILL", "SHUTDOWN", "INTO", "COPY", "DO"
    ] as Set
    private static final Set<String> ALLOWED_PLACEHOLDERS = ["windowStart", "windowEnd"] as Set
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile(/:([A-Za-z_][A-Za-z0-9_]*)/)

    /**
     * Strips string literals and comments in a single left-to-right scan so that ambiguity between
     * a comment and a quote is always resolved by whichever construct opens first. Two independent
     * regex passes (the previous approach) cannot express this: a decoy quote inside a comment could
     * pair with a later real quote and the string-literal pass would swallow live SQL between them.
     *
     * MySQL executable version comments (/*! ... *\/) are rejected outright rather than parsed, since
     * MySQL executes their body as live SQL and no legitimate reconciliation SELECT needs them.
     *
     * Unterminated string literals and unterminated block comments fail CLOSED: an explicit error is
     * pushed and the scan keeps going through the body (never silently swallowing the tail), so the
     * returned error list is guaranteed non-empty regardless of what the fallthrough content contains.
     */
    private static Map stripLiteralsAndComments(String raw) {
        StringBuilder out = new StringBuilder()
        List<String> errors = []
        int n = raw.length()
        int i = 0
        while (i < n) {
            char c = raw.charAt(i)
            if (c == ('\'' as char)) {
                int j = i + 1
                boolean closed = false
                while (j < n) {
                    char cj = raw.charAt(j)
                    if (cj == ('\\' as char) && j + 1 < n) { j += 2; continue }
                    if (cj == ('\'' as char)) {
                        if (j + 1 < n && raw.charAt(j + 1) == ('\'' as char)) { j += 2; continue }
                        j += 1
                        closed = true
                        break
                    }
                    j += 1
                }
                if (closed) {
                    out.append("''")
                    i = j
                } else {
                    errors << "Unterminated string literal."
                    // Fail closed: do not swallow the tail. Keep scanning the body so any live SQL
                    // after the lone quote still trips the downstream checks too.
                    out.append(c)
                    i += 1
                }
            } else if (c == ('"' as char)) {
                int j = i + 1
                boolean closed = false
                while (j < n) {
                    char cj = raw.charAt(j)
                    if (cj == ('\\' as char) && j + 1 < n) { j += 2; continue }
                    if (cj == ('"' as char)) {
                        if (j + 1 < n && raw.charAt(j + 1) == ('"' as char)) { j += 2; continue }
                        j += 1
                        closed = true
                        break
                    }
                    j += 1
                }
                if (closed) {
                    out.append('""')
                    i = j
                } else {
                    errors << "Unterminated string literal."
                    out.append(c)
                    i += 1
                }
            } else if (c == ('-' as char) && i + 1 < n && raw.charAt(i + 1) == ('-' as char)) {
                int nl = raw.indexOf('\n', i)
                out.append(' ')
                i = (nl < 0) ? n : nl
            } else if (c == ('#' as char)) {
                int nl = raw.indexOf('\n', i)
                out.append(' ')
                i = (nl < 0) ? n : nl
            } else if (c == ('/' as char) && i + 1 < n && raw.charAt(i + 1) == ('*' as char)) {
                if (i + 2 < n && raw.charAt(i + 2) == ('!' as char)) {
                    errors << "Executable comments (/*! ... */) are not allowed."
                    // Do not treat as a comment: fall through character-by-character so any live SQL
                    // hidden in the body (e.g. a stacked statement) still trips the checks below too.
                    out.append(c)
                    i += 1
                } else {
                    int end = raw.indexOf("*/", i + 2)
                    if (end < 0) {
                        errors << "Unterminated comment."
                        // Fail closed: keep scanning the body instead of discarding the tail.
                        out.append(c)
                        i += 1
                    } else {
                        out.append(' ')
                        i = end + 2
                    }
                }
            } else {
                out.append(c)
                i += 1
            }
        }
        return [stripped: out.toString(), errors: errors]
    }

    /**
     * Returns the [start, end) character spans of `raw` that fall inside a string literal or comment
     * (single/double-quoted literal, --, #, or /* ... *&#47;), using the same left-to-right precedence
     * as stripLiteralsAndComments (whichever construct opens first wins). Shared with
     * DatabaseExtractionSupport.prepareStatementSql so a :windowStart/:windowEnd token that appears
     * inside one of these regions is never mistaken for a real bind placeholder — it is text data, not
     * SQL, and substituting it with '?' would desync the bind-name list from the true parameter count.
     *
     * This intentionally replicates stripLiteralsAndComments's scanning state machine rather than
     * sharing its implementation: that method also collects errors and rewrites content for validation
     * purposes, a different contract than "just tell me which ranges are non-code". By the time
     * prepareStatementSql runs, SqlSelectValidator.validate has already rejected unterminated
     * literals/comments, so this method treats them the same conservative way (extends to end-of-string)
     * without needing to raise its own errors.
     */
    static List<int[]> literalAndCommentSpans(String raw) {
        List<int[]> spans = []
        int n = raw.length()
        int i = 0
        while (i < n) {
            char c = raw.charAt(i)
            if (c == ('\'' as char) || c == ('"' as char)) {
                char quote = c
                int start = i
                int j = i + 1
                while (j < n) {
                    char cj = raw.charAt(j)
                    if (cj == ('\\' as char) && j + 1 < n) { j += 2; continue }
                    if (cj == quote) {
                        if (j + 1 < n && raw.charAt(j + 1) == quote) { j += 2; continue }
                        j += 1
                        break
                    }
                    j += 1
                }
                spans << ([start, j] as int[])
                i = j
            } else if (c == ('-' as char) && i + 1 < n && raw.charAt(i + 1) == ('-' as char)) {
                int nl = raw.indexOf('\n', i)
                int end = (nl < 0) ? n : nl
                spans << ([i, end] as int[])
                i = end
            } else if (c == ('#' as char)) {
                int nl = raw.indexOf('\n', i)
                int end = (nl < 0) ? n : nl
                spans << ([i, end] as int[])
                i = end
            } else if (c == ('/' as char) && i + 1 < n && raw.charAt(i + 1) == ('*' as char)) {
                int close = raw.indexOf("*/", i + 2)
                int end = (close < 0) ? n : close + 2
                spans << ([i, end] as int[])
                i = end
            } else {
                i += 1
            }
        }
        return spans
    }

    static List<String> validate(String sql) {
        List<String> errors = []
        String raw = sql?.trim()
        if (!raw) return ["SQL is required."]

        Map scan = stripLiteralsAndComments(raw)
        String stripped = ((String) scan.stripped).trim()
        errors.addAll((List<String>) scan.errors)

        if (!stripped) {
            errors << "SQL contains no statement."
            return errors.unique()
        }
        if (stripped.contains(";")) errors << "Only a single statement is allowed (no semicolons)."
        if (stripped.contains("@")) errors << "User variables (@...) are not allowed."

        String firstWord = (stripped =~ /^\s*([A-Za-z]+)/).with { Matcher m -> m.find() ? m.group(1).toUpperCase() : "" }
        if (!(firstWord in ["SELECT", "WITH"])) errors << "Statement must start with SELECT or WITH (got '${firstWord}')."

        for (String token : stripped.toUpperCase().split(/[^A-Z0-9_]+/)) {
            if (token in DENIED_TOKENS) errors << "Keyword '${token}' is not allowed in extraction SQL."
        }
        if (stripped.toUpperCase() =~ /FOR\s+(UPDATE|SHARE)/) errors << "FOR UPDATE / FOR SHARE is not allowed."
        if (stripped.toUpperCase() =~ /LOCK\s+IN\s+SHARE\s+MODE/) errors << "LOCK IN SHARE MODE is not allowed."

        Matcher placeholders = NAMED_PLACEHOLDER.matcher(stripped)
        while (placeholders.find()) {
            String name = placeholders.group(1)
            if (!(name in ALLOWED_PLACEHOLDERS)) errors << "Unknown placeholder :${name} (only :windowStart and :windowEnd are supported)."
        }
        return errors.unique()
    }
}
