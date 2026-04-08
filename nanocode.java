///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2

import module java.base;

import static java.lang.System.getenv;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * nanocode - minimal claude code alternative (Java port)
 * Original: https://github.com/1rgs/nanocode
 */

static final ObjectMapper JSON = new ObjectMapper();

static final PrintStream IO = System.out;

static final String OPENROUTER_KEY = getenv("OPENROUTER_API_KEY");
static final String API_URL = OPENROUTER_KEY != null
        ? "https://openrouter.ai/api/v1/messages"
        : "https://api.anthropic.com/v1/messages";
static final String MODEL = Optional.ofNullable(getenv("MODEL"))
        .orElse(OPENROUTER_KEY != null ? "anthropic/claude-opus-4.5" : "claude-opus-4-5");

// ANSI colors
static final String RESET = "\033[0m", BOLD = "\033[1m", DIM = "\033[2m";
static final String BLUE = "\033[34m", CYAN = "\033[36m", GREEN = "\033[32m";
static final String YELLOW = "\033[33m", RED = "\033[31m";

// --- Tool implementations ---

static String toolRead(JsonNode args) throws IOException {
    var path = args.get("path").asText();
    var lines = Files.readAllLines(Path.of(path));
    int offset = args.has("offset") ? args.get("offset").asInt() : 0;
    int limit = args.has("limit") ? args.get("limit").asInt() : lines.size();
    var sb = new StringBuilder();
    for (int i = offset; i < Math.min(offset + limit, lines.size()); i++) {
        sb.append(String.format("%4d| %s%n", i + 1, lines.get(i)));
    }
    return sb.toString();
}

static String toolWrite(JsonNode args) throws IOException {
    writeString(Path.of(args.get("path").asText()), args.get("content").asText());
    return "ok";
}

static String toolEdit(JsonNode args) throws IOException {
    var path = Path.of(args.get("path").asText());
    var text = readString(path);
    var old = args.get("old").asText();
    var replacement = args.get("new").asText();
    boolean all = args.has("all") && args.get("all").asBoolean();

    if (!text.contains(old))
        return "error: old_string not found";

    long count = countOccurrences(text, old);
    if (!all && count > 1) {
        return "error: old_string appears " + count + " times, must be unique (use all=true)";
    }

    String result = all ? text.replace(old, replacement) : replaceFirst(text, old, replacement);
    writeString(path, result);
    return "ok";
}

static long countOccurrences(String text, String sub) {
    long count = 0;
    int idx = 0;
    while ((idx = text.indexOf(sub, idx)) != -1) {
        count++;
        idx += sub.length();
    }
    return count;
}

static String replaceFirst(String text, String old, String replacement) {
    int idx = text.indexOf(old);
    if (idx == -1)
        return text;
    return text.substring(0, idx) + replacement + text.substring(idx + old.length());
}

static String toolGlob(JsonNode args) throws IOException {
    var basePath = args.has("path") ? args.get("path").asText() : ".";
    var pattern = args.get("pat").asText();
    var fullPattern = (basePath + "/" + pattern).replace("//", "/");

    // Use PathMatcher with glob
    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + fullPattern);
    var files = new ArrayList<Path>();

    var root = Path.of(basePath);
    if (!exists(root))
        return "none";

    Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file))
                files.add(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    });

    files.sort((a, b) -> {
        try {
            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
        } catch (IOException e) {
            return 0;
        }
    });

    return files.isEmpty() ? "none" : files.stream().map(Path::toString).collect(Collectors.joining("\n"));
}

static String toolGrep(JsonNode args) throws IOException {
    var pattern = Pattern.compile(args.get("pat").asText());
    var basePath = args.has("path") ? args.get("path").asText() : ".";
    var hits = new ArrayList<String>();

    Files.walkFileTree(Path.of(basePath), new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile())
                return FileVisitResult.CONTINUE;
            try {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size() && hits.size() < 50; i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        hits.add(file + ":" + (i + 1) + ":" + lines.get(i));
                    }
                }
            } catch (Exception e) {
                /* skip binary/unreadable files */ }
            return hits.size() >= 50 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    });

    return hits.isEmpty() ? "none" : String.join("\n", hits);
}

static String toolBash(JsonNode args) throws IOException, InterruptedException {
    var cmd = args.get("cmd").asText();
    var pb = new ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true);
    var proc = pb.start();
    var outputLines = new ArrayList<String>();

    try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
            IO.println("  " + DIM + "│ " + line + RESET);
            IO.flush();
            outputLines.add(line);
        }
    }

    if (!proc.waitFor(30, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        outputLines.add("(timed out after 30s)");
    }

    return outputLines.isEmpty() ? "(empty)" : String.join("\n", outputLines);
}

// --- Tool dispatch ---

static String runTool(String name, JsonNode args) {
    try {
        return switch (name) {
            case "read" -> toolRead(args);
            case "write" -> toolWrite(args);
            case "edit" -> toolEdit(args);
            case "glob" -> toolGlob(args);
            case "grep" -> toolGrep(args);
            case "bash" -> toolBash(args);
            default -> "error: unknown tool " + name;
        };
    } catch (Exception e) {
        return "error: " + e.getMessage();
    }
}

// --- Tool schema for API ---

record ToolDef(String name, String description, String[][] params) {
}

static final ToolDef[] TOOL_DEFS = {
        new ToolDef("read", "Read file with line numbers (file path, not directory)",
                new String[][] { { "path", "string", "true" }, { "offset", "integer", "false" },
                        { "limit", "integer", "false" } }),
        new ToolDef("write", "Write content to file",
                new String[][] { { "path", "string", "true" }, { "content", "string", "true" } }),
        new ToolDef("edit", "Replace old with new in file (old must be unique unless all=true)",
                new String[][] { { "path", "string", "true" }, { "old", "string", "true" }, { "new", "string", "true" },
                        { "all", "boolean", "false" } }),
        new ToolDef("glob", "Find files by pattern, sorted by mtime",
                new String[][] { { "pat", "string", "true" }, { "path", "string", "false" } }),
        new ToolDef("grep", "Search files for regex pattern",
                new String[][] { { "pat", "string", "true" }, { "path", "string", "false" } }),
        new ToolDef("bash", "Run shell command",
                new String[][] { { "cmd", "string", "true" } }),
};

static ArrayNode makeSchema() {
    var arr = JSON.createArrayNode();
    for (var td : TOOL_DEFS) {
        var tool = JSON.createObjectNode();
        tool.put("name", td.name());
        tool.put("description", td.description());
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = JSON.createObjectNode();
        var required = JSON.createArrayNode();
        for (var p : td.params()) {
            var prop = JSON.createObjectNode();
            prop.put("type", p[1]);
            props.set(p[0], prop);
            if ("true".equals(p[2]))
                required.add(p[0]);
        }
        schema.set("properties", props);
        schema.set("required", required);
        tool.set("input_schema", schema);
        arr.add(tool);
    }
    return arr;
}

// --- API call ---

static JsonNode callApi(ArrayNode messages, String systemPrompt) throws IOException {
    var body = JSON.createObjectNode();
    body.put("model", MODEL);
    body.put("max_tokens", 8192);
    body.put("system", systemPrompt);
    body.set("messages", messages);
    body.set("tools", makeSchema());

    var conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("anthropic-version", "2023-06-01");
    if (OPENROUTER_KEY != null) {
        conn.setRequestProperty("Authorization", "Bearer " + OPENROUTER_KEY);
    } else {
        conn.setRequestProperty("x-api-key", Optional.ofNullable(getenv("ANTHROPIC_API_KEY")).orElse(""));
    }

    try (var os = conn.getOutputStream()) {
        os.write(JSON.writeValueAsBytes(body));
    }

    int status = conn.getResponseCode();
    InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    var response = JSON.readTree(is);

    if (status >= 400) {
        throw new IOException("API error " + status + ": " + response);
    }

    return response;
}

// --- UI helpers ---

static String separator() {
    int cols = 80;
    try {
        var proc = new ProcessBuilder("tput", "cols").inheritIO().redirectOutput(ProcessBuilder.Redirect.PIPE).start();
        var out = new String(proc.getInputStream().readAllBytes()).trim();
        proc.waitFor();
        cols = Math.min(Integer.parseInt(out), 80);
    } catch (Exception e) {
        /* default 80 */ }
    return DIM + "─".repeat(cols) + RESET;
}

static String renderMarkdown(String text) {
    return Pattern.compile("\\*\\*(.+?)\\*\\*").matcher(text)
            .replaceAll(BOLD + "$1" + RESET);
}

// --- Main ---

void main(String[] args) throws Exception {
    var cwd = System.getProperty("user.dir");
    var provider = OPENROUTER_KEY != null ? "OpenRouter" : "Anthropic";
    IO.println(BOLD + "nanocode" + RESET + " | " + DIM + MODEL + " (" + provider + ") | " + cwd + RESET + "\n");

    var messages = JSON.createArrayNode();
    var systemPrompt = "Concise coding assistant. cwd: " + cwd;
    var stdin = new BufferedReader(new InputStreamReader(System.in));

    while (true) {
        try {
            IO.println(separator());
            IO.print(BOLD + BLUE + "❯" + RESET + " ");
            IO.flush();
            String userInput = stdin.readLine();
            if (userInput == null)
                break; // EOF
            userInput = userInput.strip();
            IO.println(separator());

            if (userInput.isEmpty())
                continue;
            if (userInput.equals("/q") || userInput.equals("exit"))
                break;
            if (userInput.equals("/c")) {
                messages = JSON.createArrayNode();
                IO.println(GREEN + "⏺ Cleared conversation" + RESET);
                continue;
            }

            var userMsg = JSON.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userInput);
            messages.add(userMsg);

            // Agentic loop: keep calling API until no more tool calls
            while (true) {
                var response = callApi(messages, systemPrompt);
                var contentBlocks = response.get("content");
                var toolResults = JSON.createArrayNode();

                for (var block : contentBlocks) {
                    var type = block.get("type").asText();

                    if ("text".equals(type)) {
                        IO.println("\n" + CYAN + "⏺" + RESET + " " + renderMarkdown(block.get("text").asText()));
                    }

                    if ("tool_use".equals(type)) {
                        var toolName = block.get("name").asText();
                        var toolArgs = block.get("input");
                        // Preview: first argument value, max 50 chars
                        String argPreview = "";
                        var fields = toolArgs.fields();
                        if (fields.hasNext()) {
                            argPreview = fields.next().getValue().asText();
                            if (argPreview.length() > 50)
                                argPreview = argPreview.substring(0, 50);
                        }
                        IO.println("\n" + GREEN + "⏺ " + capitalize(toolName) + RESET
                                + "(" + DIM + argPreview + RESET + ")");

                        var result = runTool(toolName, toolArgs);
                        var resultLines = result.split("\n");
                        var preview = resultLines[0].length() > 60 ? resultLines[0].substring(0, 60) : resultLines[0];
                        if (resultLines.length > 1) {
                            preview += " ... +" + (resultLines.length - 1) + " lines";
                        } else if (resultLines[0].length() > 60) {
                            preview += "...";
                        }
                        IO.println("  " + DIM + "⎿  " + preview + RESET);

                        var tr = JSON.createObjectNode();
                        tr.put("type", "tool_result");
                        tr.put("tool_use_id", block.get("id").asText());
                        tr.put("content", result);
                        toolResults.add(tr);
                    }
                }

                var assistantMsg = JSON.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.set("content", contentBlocks);
                messages.add(assistantMsg);

                if (toolResults.isEmpty())
                    break;

                var toolMsg = JSON.createObjectNode();
                toolMsg.put("role", "user");
                toolMsg.set("content", toolResults);
                messages.add(toolMsg);
            }

            IO.println();

        } catch (Exception e) {
            if (e instanceof EOFException)
                break;
            IO.println(RED + "⏺ Error: " + e.getMessage() + RESET);
        }
    }
}

static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
}
