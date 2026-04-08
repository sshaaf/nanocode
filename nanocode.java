///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//DEPS org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0
//SOURCES JavaLspSupport.java

import module java.base;

import static java.lang.System.getenv;
import static java.nio.file.Files.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * nanocode - minimal claude code alternative. Original:
 * https://github.com/1rgs/nanocode
 */

static final ObjectMapper JSON = new ObjectMapper();

// Tool result constants
static final String RESULT_OK = "ok";
static final String RESULT_NONE = "none";
static final String RESULT_EMPTY = "(empty)";
static final String ERROR_PREFIX = "error: ";

// Provider detection - computed once
enum Provider {
    ANTHROPIC("https://api.anthropic.com/v1/messages", "claude-opus-4-5"),
    OPENROUTER("https://openrouter.ai/api/v1/messages", "anthropic/claude-opus-4.5"),
    OPENAI("https://api.openai.com/v1/chat/completions", "gpt-4o");

    final String url;
    final String defaultModel;

    Provider(String url, String defaultModel) {
        this.url = url;
        this.defaultModel = defaultModel;
    }

    static Provider detect() {
        String orKey = getenv("OPENROUTER_API_KEY");
        String oaKey = getenv("OPENAI_API_KEY");
        if (orKey != null && !orKey.isBlank())
            return OPENROUTER;
        if (oaKey != null && !oaKey.isBlank())
            return OPENAI;
        return ANTHROPIC;
    }
}

static final Provider PROVIDER = Provider.detect();
static final String MODEL = Optional.ofNullable(getenv("MODEL")).orElseGet(() -> PROVIDER.defaultModel);

static String getApiKey() {
    return switch (PROVIDER) {
        case OPENROUTER -> getenv("OPENROUTER_API_KEY");
        case OPENAI -> getenv("OPENAI_API_KEY");
        case ANTHROPIC -> getenv("ANTHROPIC_API_KEY");
    };
}

static final String RESET = "\033[0m", BOLD = "\033[1m", DIM = "\033[2m";
static final String BLUE = "\033[34m", CYAN = "\033[36m", GREEN = "\033[32m", RED = "\033[31m";

// --- Tools ---

static String toolRead(JsonNode args) throws IOException {
    var lines = readAllLines(Path.of(args.get("path").asText()));
    int offset = args.path("offset").asInt(0), limit = args.path("limit").asInt(lines.size());
    return IntStream.range(offset, Math.min(offset + limit, lines.size()))
            .mapToObj(i -> "%4d| %s".formatted(i + 1, lines.get(i)))
            .collect(Collectors.joining("\n"));
}

static String toolWrite(JsonNode args) throws IOException {
    writeString(Path.of(args.get("path").asText()), args.get("content").asText());
    return RESULT_OK;
}

static String toolEdit(JsonNode args) throws IOException {
    var path = Path.of(args.get("path").asText());
    var text = readString(path);
    var old = args.get("old").asText();
    var repl = args.get("new").asText();
    if (!text.contains(old))
        return ERROR_PREFIX + "old_string not found";
    // Efficient counting: split and count parts
    int count = text.split(Pattern.quote(old), -1).length - 1;
    if (!args.path("all").asBoolean() && count > 1)
        return ERROR_PREFIX + "old_string appears " + count + " times, must be unique (use all=true)";
    writeString(path, args.path("all").asBoolean()
            ? text.replace(old, repl)
            : text.replaceFirst(Pattern.quote(old), Matcher.quoteReplacement(repl)));
    return RESULT_OK;
}

static String toolGlob(JsonNode args) throws IOException {
    var base = Path.of(args.path("path").asText("."));
    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + base + "/" + args.get("pat").asText());
    if (!exists(base))
        return RESULT_NONE;
    record FileWithTime(Path path, FileTime mtime) {}
    try (var walk = walk(base)) {
        var files = walk.filter(Files::isRegularFile)
                .filter(matcher::matches)
                .map(p -> {
                    try {
                        return new FileWithTime(p, getLastModifiedTime(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .sorted((a, b) -> b.mtime.compareTo(a.mtime))
                .map(f -> f.path.toString())
                .toList();
        return files.isEmpty() ? RESULT_NONE : String.join("\n", files);
    } catch (UncheckedIOException e) {
        throw e.getCause();
    }
}

static String toolGrep(JsonNode args) throws IOException {
    var pattern = Pattern.compile(args.get("pat").asText());
    var base = Path.of(args.path("path").asText("."));
    var hits = new ArrayList<String>();
    try (var walk = walk(base)) {
        walk.filter(Files::isRegularFile).takeWhile(_ -> hits.size() < 50).forEach(file -> {
            try {
                var lines = readAllLines(file);
                for (int i = 0; i < lines.size() && hits.size() < 50; i++)
                    if (pattern.matcher(lines.get(i)).find())
                        hits.add(file + ":" + (i + 1) + ":" + lines.get(i));
            } catch (Exception ignored) {
                /* skip unreadable files */ }
        });
    }
    return hits.isEmpty() ? RESULT_NONE : String.join("\n", hits);
}

static String toolJavaDefinition(JsonNode args) throws Exception {
    return JavaLspSupport.definition(args.get("path").asText(), args.path("line").asInt(1),
            args.path("column").asInt(1));
}

static String toolJavaHover(JsonNode args) throws Exception {
    return JavaLspSupport.hover(args.get("path").asText(), args.path("line").asInt(1),
            args.path("column").asInt(1));
}

static String toolJavaDiagnostics(JsonNode args) throws Exception {
    return JavaLspSupport.diagnostics(args.path("path").asText(""));
}

static String toolBash(JsonNode args) throws Exception {
    var proc = new ProcessBuilder("sh", "-c", args.get("cmd").asText()).redirectErrorStream(true).start();
    var out = new ArrayList<String>();
    try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = r.readLine()) != null) {
            System.out.println("  " + DIM + "│ " + line + RESET);
            out.add(line);
        }
    }
    if (!proc.waitFor(30, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        out.add("(timed out after 30s)");
    }
    return out.isEmpty() ? RESULT_EMPTY : String.join("\n", out);
}

static String runTool(String name, JsonNode args, boolean javaLsp) {
    try {
        return switch (name) {
            case "read" -> toolRead(args);
            case "write" -> toolWrite(args);
            case "edit" -> toolEdit(args);
            case "glob" -> toolGlob(args);
            case "grep" -> toolGrep(args);
            case "bash" -> toolBash(args);
            case "java_definition" -> {
                if (!javaLsp)
                    yield ERROR_PREFIX + "Java LSP not enabled";
                yield toolJavaDefinition(args);
            }
            case "java_hover" -> {
                if (!javaLsp)
                    yield ERROR_PREFIX + "Java LSP not enabled";
                yield toolJavaHover(args);
            }
            case "java_diagnostics" -> {
                if (!javaLsp)
                    yield ERROR_PREFIX + "Java LSP not enabled";
                yield toolJavaDiagnostics(args);
            }
            default -> ERROR_PREFIX + "unknown tool " + name;
        };
    } catch (Exception e) {
        return ERROR_PREFIX + e.getMessage();
    }
}

// --- Schema ---

static final String SCHEMA_BASE = """
        [{"name":"read","description":"Read file with line numbers (file path, not directory)","input_schema":{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer"},"limit":{"type":"integer"}},"required":["path"]}},
        {"name":"write","description":"Write content to file","input_schema":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}},
        {"name":"edit","description":"Replace old with new in file (old must be unique unless all=true)","input_schema":{"type":"object","properties":{"path":{"type":"string"},"old":{"type":"string"},"new":{"type":"string"},"all":{"type":"boolean"}},"required":["path","old","new"]}},
        {"name":"glob","description":"Find files matching glob pat under path (default .). pat is NOT recursive unless you use ** (e.g. **/*.java, **/AbstractCommand.java)","input_schema":{"type":"object","properties":{"pat":{"type":"string"},"path":{"type":"string"}},"required":["pat"]}},
        {"name":"grep","description":"Search files for regex pattern","input_schema":{"type":"object","properties":{"pat":{"type":"string"},"path":{"type":"string"}},"required":["pat"]}},
        {"name":"bash","description":"Run shell command","input_schema":{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}}]""";

static final String SCHEMA_JAVA_LSP = """
        [{"name":"java_definition","description":"Go to where a symbol is declared (navigation). Use for where is X defined — NOT for what type is X (use java_hover). path: prefer short workspace-relative path e.g. src/main/java/.../Foo.java. line/column: 1-based, cursor on the symbol","input_schema":{"type":"object","properties":{"path":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"}},"required":["path"]}},
        {"name":"java_hover","description":"Type, signature, Javadoc, enum constant docs at cursor — use for what type is this, what does this field/enum constant mean in source, explain this identifier in the project. path: prefer workspace-relative. line/column: 1-based; put column on the identifier","input_schema":{"type":"object","properties":{"path":{"type":"string"},"line":{"type":"integer"},"column":{"type":"integer"}},"required":["path"]}},
        {"name":"java_diagnostics","description":"Java compiler/LSP errors and warnings; omit path for all cached diagnostics","input_schema":{"type":"object","properties":{"path":{"type":"string"}}}}]""";

static ArrayNode toolsSchema(boolean javaLsp) throws IOException {
    var schema = (ArrayNode) JSON.readTree(SCHEMA_BASE);
    if (javaLsp) {
        for (var n : JSON.readTree(SCHEMA_JAVA_LSP))
            schema.add(n);
    }
    return schema;
}

// --- API ---

static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

static ArrayNode anthropicToolsToOpenAi(ArrayNode anthropicTools) {
    var out = JSON.createArrayNode();
    for (JsonNode t : anthropicTools) {
        var fn = JSON.createObjectNode();
        fn.put("name", t.get("name").asText());
        fn.put("description", t.get("description").asText());
        fn.set("parameters", t.get("input_schema"));
        var tool = JSON.createObjectNode();
        tool.put("type", "function");
        tool.set("function", fn);
        out.add(tool);
    }
    return out;
}

static JsonNode callOpenAi(ArrayNode conversationMessages, String systemPrompt, ArrayNode openAiTools)
        throws IOException, InterruptedException {
    var fullMessages = JSON.createArrayNode();
    fullMessages.add(JSON.createObjectNode().put("role", "system").put("content", systemPrompt));
    fullMessages.addAll(conversationMessages);
    var body = JSON.createObjectNode();
    body.put("model", MODEL);
    body.put("max_tokens", 8192);
    body.set("messages", fullMessages);
    body.set("tools", openAiTools);
    body.put("tool_choice", "auto");

    var request = HttpRequest.newBuilder()
            .uri(URI.create(PROVIDER.url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();

    var resp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    var response = JSON.readTree(resp.body());
    if (resp.statusCode() >= 400)
        throw new IOException("API error " + resp.statusCode() + ": " + response);
    return response;
}

static JsonNode callApi(ArrayNode messages, String systemPrompt, ArrayNode tools)
        throws IOException, InterruptedException {
    var body = JSON.createObjectNode().put("model", MODEL).put("max_tokens", 8192).put("system", systemPrompt);
    body.set("messages", messages);
    body.set("tools", tools);

    var requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(PROVIDER.url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("anthropic-version", "2023-06-01");

    if (PROVIDER == Provider.OPENROUTER) {
        requestBuilder.header("Authorization", "Bearer " + getApiKey());
    } else {
        requestBuilder.header("x-api-key", getApiKey());
    }

    var request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();

    var resp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    var response = JSON.readTree(resp.body());
    if (resp.statusCode() >= 400)
        throw new IOException("API error " + resp.statusCode() + ": " + response);
    return response;
}

// --- UI ---

static int cachedTermWidth = -1;

static int getTermWidth() {
    if (cachedTermWidth > 0)
        return cachedTermWidth;
    try {
        var p = new ProcessBuilder("tput", "cols").redirectErrorStream(true).start();
        cachedTermWidth = Math.min(Integer.parseInt(new String(p.getInputStream().readAllBytes()).trim()), 80);
    } catch (Exception e) {
        cachedTermWidth = 80;
    }
    return cachedTermWidth;
}

static String sep() {
    return DIM + "─".repeat(getTermWidth()) + RESET;
}

static String preview(String s, int max) {
    int newlineIdx = s.indexOf('\n');
    String firstLine = newlineIdx == -1 ? s : s.substring(0, newlineIdx);
    String preview = firstLine.substring(0, Math.min(firstLine.length(), max));

    if (newlineIdx != -1) {
        int lineCount = (int) s.chars().filter(ch -> ch == '\n').count();
        return preview + " ... +" + lineCount + " lines";
    }
    return firstLine.length() > max ? preview + "..." : preview;
}

/** Terminal line for tool call — prefer full `path` tail so long dirs are not misleadingly truncated. */
static String toolArgsPreview(JsonNode toolArgs) {
    if (toolArgs == null || !toolArgs.isObject())
        return "";
    var path = toolArgs.path("path").asText("");
    if (!path.isBlank()) {
        int max = 76;
        if (path.length() <= max)
            return path;
        return "…" + path.substring(path.length() - (max - 1));
    }
    var it = toolArgs.fields();
    if (!it.hasNext())
        return "";
    var s = it.next().getValue().asText("");
    int m = 56;
    return s.length() <= m ? s : s.substring(0, m - 1) + "…";
}

// --- Main ---

void main(String[] args) throws Exception {
    var cwd = System.getProperty("user.dir");
    System.out.println(BOLD + "nanocode" + RESET + " | " + DIM + MODEL + " (" + PROVIDER + ") | " + cwd + RESET + "\n");

    var messages = JSON.createArrayNode();
    String systemPrompt = "Concise coding assistant. cwd: " + cwd
            + " When the user names types, files, methods, or enum constants that could exist in this workspace, prefer read/glob and (if Java LSP is on) java_hover to ground the answer in the repo — not generic product docs alone.";
    var stdin = new BufferedReader(new InputStreamReader(System.in));

    boolean javaLspEnabled = false;
    var cwdPath = Path.of(cwd);
    if (JavaLspSupport.workspaceHasJavaFiles(cwdPath) && !"1".equals(System.getenv("NANOCODE_NO_JAVA_LSP"))) {
        boolean want = "1".equals(System.getenv("NANOCODE_JAVA_LSP"));
        if (!want) {
            System.out.print(DIM + "This workspace contains .java files. Download & enable Java LSP (JDT, ~50MB once)? [y/N] "
                    + RESET);
            System.out.flush();
            var y = stdin.readLine();
            want = y != null && y.strip().equalsIgnoreCase("y");
        }
        if (want) {
            try {
                JavaLspSupport.installIfNeeded(msg -> System.out.println(DIM + "  " + msg + RESET));
                JavaLspSupport.start(cwdPath, msg -> System.out.println(DIM + "  " + msg + RESET));
                javaLspEnabled = true;
                systemPrompt += " Java LSP (JDT): java_definition = go to declaration; java_hover = type/Javadoc/docs at cursor (what type, what a field or enum constant is in this code — not only \"what type\" phrasing); java_diagnostics = compile issues. Line/column 1-based."
                        + " Prefer workspace-relative paths for read and java_* (src/main/java/.../Foo.java), not long absolute paths that may get cut off."
                        + " For glob from repo root use ** for recursive, e.g. **/AbstractCommand.java or **/*.java.";
                System.out.println(GREEN + "⏺ Java LSP enabled" + RESET + "\n");
            } catch (Exception e) {
                System.out.println(RED + "⏺ Java LSP failed: " + e.getMessage() + RESET + "\n");
            }
        }
    }

    var tools = toolsSchema(javaLspEnabled);
    var openAiTools = PROVIDER == Provider.OPENAI ? anthropicToolsToOpenAi(tools) : null;

    while (true) {
        try {
            System.out.println(sep());
            System.out.print(BOLD + BLUE + "❯" + RESET + " ");
            System.out.flush();
            var input = stdin.readLine();
            if (input == null)
                break;
            input = input.strip();
            System.out.println(sep());
            if (input.isEmpty())
                continue;
            if (input.equals("/q") || input.equals("exit"))
                break;
            if (input.equals("/c")) {
                messages = JSON.createArrayNode();
                System.out.println(GREEN + "⏺ Cleared" + RESET);
                continue;
            }

            messages.add(JSON.createObjectNode().put("role", "user").put("content", input));

            while (true) {
                if (PROVIDER == Provider.OPENAI) {
                    var response = callOpenAi(messages, systemPrompt, openAiTools);
                    var msg = response.get("choices").get(0).get("message");
                    if (msg.has("content") && !msg.get("content").isNull()) {
                        var txt = msg.get("content").asText().strip();
                        if (!txt.isEmpty())
                            System.out.println("\n" + CYAN + "⏺" + RESET + " "
                                    + txt.replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET));
                    }
                    var tc = msg.get("tool_calls");
                    if (tc == null || !tc.isArray() || tc.isEmpty()) {
                        messages.add(msg.deepCopy());
                        break;
                    }
                    messages.add(msg.deepCopy());
                    for (var toolCall : tc) {
                        var id = toolCall.get("id").asText();
                        var fn = toolCall.get("function");
                        var name = fn.get("name").asText();
                        var argStr = fn.path("arguments").asText("");
                        var toolArgs = argStr.isBlank() ? JSON.createObjectNode() : JSON.readTree(argStr);
                        var argPreview = toolArgsPreview(toolArgs);
                        System.out.println("\n" + GREEN + "⏺ " + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                                + RESET + "(" + DIM + argPreview + RESET + ")");
                        var result = runTool(name, toolArgs, javaLspEnabled);
                        System.out.println("  " + DIM + "⎿  " + preview(result, 60) + RESET);
                        messages.add(JSON.createObjectNode().put("role", "tool").put("tool_call_id", id)
                                .put("content", result));
                    }
                } else {
                    var response = callApi(messages, systemPrompt, tools);
                    var content = response.get("content");
                    var toolResults = JSON.createArrayNode();

                    for (var block : content) {
                        if ("text".equals(block.get("type").asText()))
                            System.out.println("\n" + CYAN + "⏺" + RESET + " "
                                    + block.get("text").asText().replaceAll("\\*\\*(.+?)\\*\\*", BOLD + "$1" + RESET));

                        if ("tool_use".equals(block.get("type").asText())) {
                            var name = block.get("name").asText();
                            var toolArgs = block.get("input");
                            var argPreview = toolArgsPreview(toolArgs);
                            System.out.println("\n" + GREEN + "⏺ " + Character.toUpperCase(name.charAt(0))
                                    + name.substring(1) + RESET + "(" + DIM + argPreview + RESET + ")");

                            var result = runTool(name, toolArgs, javaLspEnabled);
                            System.out.println("  " + DIM + "⎿  " + preview(result, 60) + RESET);

                            toolResults.add(JSON.createObjectNode().put("type", "tool_result")
                                    .put("tool_use_id", block.get("id").asText()).put("content", result));
                        }
                    }

                    messages.add(JSON.createObjectNode().put("role", "assistant").<ObjectNode>set("content", content.deepCopy()));
                    if (toolResults.isEmpty())
                        break;
                    messages.add(JSON.createObjectNode().put("role", "user").<ObjectNode>set("content", toolResults));
                }
            }
            System.out.println();
        } catch (Exception e) {
            if (e instanceof EOFException)
                break;
            System.out.println(RED + "⏺ Error: " + e.getMessage() + RESET);
        }
    }
}
