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
    private static final Pattern STRING_LITERAL = Pattern.compile(/'(?:[^'\\]|\\.|'')*'|"(?:[^"\\]|\\.)*"/)
    private static final Pattern LINE_COMMENT = Pattern.compile(/--[^\n]*|#[^\n]*/)
    private static final Pattern BLOCK_COMMENT = Pattern.compile(/\/\*.*?\*\//, Pattern.DOTALL)
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile(/:([A-Za-z_][A-Za-z0-9_]*)/)

    static List<String> validate(String sql) {
        List<String> errors = []
        String raw = sql?.trim()
        if (!raw) return ["SQL is required."]

        // Strip literals first so data values never trip token rules, then comments so nothing hides in them.
        String stripped = BLOCK_COMMENT.matcher(STRING_LITERAL.matcher(raw).replaceAll("''")).replaceAll(" ")
        stripped = LINE_COMMENT.matcher(stripped).replaceAll(" ").trim()

        if (!stripped) return ["SQL contains no statement."]
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
