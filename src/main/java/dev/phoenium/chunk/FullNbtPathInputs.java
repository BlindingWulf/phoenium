package dev.phoenium.chunk;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;

/** Reads only leading NBT path field syntax. It does not apply match nodes, indices,
 * wildcards, or predicates to live or serialized data. The original NbtPath applies
 * every node later after the original serializer has produced its tag. */
final class FullNbtPathInputs {
    private FullNbtPathInputs() { }

    static List<String> leadingFields(String source, int limit) {
        var reader = new StringReader(source); var fields = new ArrayList<String>(limit);
        try {
            skipSelectors(reader);
            while (reader.canRead() && fields.size() < limit) {
                if (reader.peek() == '.') reader.skip();
                skipSelectors(reader);
                if (!reader.canRead() || reader.peek() == '[' || reader.peek() == '{') break;
                String field = readField(reader);
                if (field.isEmpty()) break;
                fields.add(field); skipSelectors(reader);
                if (!reader.canRead() || reader.peek() != '.') break;
            }
        } catch (CommandSyntaxException malformed) {
            // Vanilla already parsed this source while decoding the Component. An empty
            // result here keeps an unexpectedly changed grammar explicitly unsupported.
            return List.of();
        }
        return List.copyOf(fields);
    }

    /** Exact finite classifier for the one vanilla cached component path considered by
     * Stage 3. This reads syntax only; it never selects or serializes NBT. */
    static boolean cachedCustomName(String source) {
        List<String> fields = leadingFields(source, 2);
        return fields.size() == 2 && fields.get(0).equals("components")
                && fields.get(1).equals("minecraft:custom_name");
    }

    private static String readField(StringReader reader) throws CommandSyntaxException {
        if (reader.peek() == '\'' || reader.peek() == '"') return reader.readString();
        int start = reader.getCursor();
        while (reader.canRead() && allowedUnquoted(reader.peek())) reader.skip();
        return reader.getString().substring(start, reader.getCursor());
    }

    // Pinned NbtPathArgument.readUnquotedName stops at exactly these node delimiters.
    private static boolean allowedUnquoted(char value) {
        return value != ' ' && value != '"' && value != '\'' && value != '[' && value != ']'
                && value != '.' && value != '{' && value != '}';
    }

    private static void skipSelectors(StringReader reader) throws CommandSyntaxException {
        while (reader.canRead() && (reader.peek() == '[' || reader.peek() == '{'))
            skipBalanced(reader, reader.peek(), reader.peek() == '[' ? ']' : '}');
    }

    private static void skipBalanced(StringReader reader, char open, char close) throws CommandSyntaxException {
        int depth = 0; char quote = 0; boolean escaped = false;
        while (reader.canRead()) {
            char current = reader.read();
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') { quote = current; continue; }
            if (current == open) depth++;
            else if (current == close && --depth == 0) return;
        }
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, String.valueOf(close));
    }
}
