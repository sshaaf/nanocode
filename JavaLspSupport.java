import module java.base;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/** Tier-2 Java support: downloads Eclipse JDT LS into the nanocode cache and drives it via LSP4J. */
public final class JavaLspSupport {

    public static final String DOWNLOAD_URL = "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz";
    private static final String BUNDLE_DIR = "jdt-ls-bundle";
    private static final String MARKER = ".nanocode-extracted";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private static final ConcurrentHashMap<String, List<Diagnostic>> DIAGNOSTICS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> DOC_VERSIONS = new ConcurrentHashMap<>();

    private static volatile LanguageServer server;
    private static volatile Process process;
    private static volatile org.eclipse.lsp4j.jsonrpc.Launcher<LanguageServer> launcher;
    private static volatile Path workspaceRoot;
    private static volatile Path jdtRoot;
    private static final JavaLanguageClient CLIENT = new JavaLanguageClient();
    private static final List<Thread> daemonThreads = Collections.synchronizedList(new ArrayList<>());

    private JavaLspSupport() {
    }

    public static Path cacheRoot() {
        var xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("nanocode");
    }

    public static boolean isActive() {
        return server != null;
    }

    /** @return short hex id for the JDT {@code -data} directory */
    public static String workspaceDataId(Path root) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d, 0, 8);
    }

    public static boolean workspaceHasJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root))
            return false;
        // Need enough depth for normal Maven layouts (src/main/java/com/.../many/segments).
        try (Stream<Path> walk = Files.walk(root, 64)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .findAny()
                    .isPresent();
        }
    }

    public static void installIfNeeded(Consumer<String> log) throws IOException, InterruptedException {
        Path cache = cacheRoot();
        Files.createDirectories(cache);
        jdtRoot = cache.resolve(BUNDLE_DIR);
        Path marker = jdtRoot.resolve(MARKER);
        if (Files.isRegularFile(marker) && findLauncherJar(jdtRoot).isPresent()) {
            log.accept("Java LSP bundle already present at " + jdtRoot);
            return;
        }
        if (Files.exists(jdtRoot))
            deleteRecursive(jdtRoot);
        Files.createDirectories(jdtRoot);

        Path archive = cache.resolve("jdt-language-server-latest.tar.gz");
        log.accept("Downloading JDT language server (~50MB)…");
        download(DOWNLOAD_URL, archive, log);

        Path tmp = cache.resolve("jdt-ls-extract-tmp");
        if (Files.exists(tmp))
            deleteRecursive(tmp);
        Files.createDirectories(tmp);

        var tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", tmp.toString())
                .inheritIO()
                .start();
        if (tar.waitFor() != 0)
            throw new IOException("tar extraction failed (exit " + tar.exitValue() + ")");

        Path nested = findJdtHomeWithPlugins(tmp).orElseThrow(
                () -> new IOException("could not locate JDT LS layout after extract"));
        moveTree(nested, jdtRoot);
        deleteRecursive(tmp);
        Files.writeString(marker, "ok\n");
        log.accept("Installed JDT LS to " + jdtRoot);
    }

    public static synchronized void start(Path workspace, Consumer<String> log) throws Exception {
        Objects.requireNonNull(workspace, "workspace");
        if (server != null)
            return;
        workspaceRoot = workspace.toAbsolutePath().normalize();
        if (jdtRoot == null)
            jdtRoot = cacheRoot().resolve(BUNDLE_DIR);
        if (!Files.isDirectory(jdtRoot) || findLauncherJar(jdtRoot).isEmpty())
            throw new IOException("JDT LS bundle missing; run installIfNeeded first");

        Path dataDir = cacheRoot().resolve("jdt-workspace").resolve(workspaceDataId(workspaceRoot));
        Files.createDirectories(dataDir);

        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        Path launcherJar = findLauncherJar(jdtRoot).orElseThrow();
        String configDir = platformConfigDir(jdtRoot);

        var cmd = new ArrayList<String>();
        cmd.add(javaBin.toString());
        cmd.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        cmd.add("-Dosgi.bundles.defaultStartLevel=4");
        cmd.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        cmd.add("-Dlog.level=WARN");
        cmd.add("-Xmx1G");
        cmd.add("--add-modules=ALL-SYSTEM");
        cmd.add("--add-opens");
        cmd.add("java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens");
        cmd.add("java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens");
        cmd.add("java.base/sun.nio.fs=ALL-UNNAMED");
        cmd.add("-jar");
        cmd.add(launcherJar.toString());
        cmd.add("-configuration");
        cmd.add(configDir);
        cmd.add("-data");
        cmd.add(dataDir.toString());

        var pb = new ProcessBuilder(cmd);
        pb.directory(jdtRoot.toFile());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        log.accept("Starting JDT language server…");
        process = pb.start();

        Thread errThread = Thread.ofPlatform().daemon(true).start(() -> drain(process.getErrorStream()));
        daemonThreads.add(errThread);

        var in = process.getInputStream();
        var out = process.getOutputStream();
        launcher = LSPLauncher.createClientLauncher(CLIENT, in, out);
        server = launcher.getRemoteProxy();
        var listen = launcher.startListening();

        CLIENT.workspaceUri = workspaceRoot.toUri().toString();
        CLIENT.workspaceName = workspaceRoot.getFileName().toString();

        var init = new InitializeParams();
        init.setProcessId((int) ProcessHandle.current().pid());
        init.setRootUri(workspaceRoot.toUri().toString());
        init.setWorkspaceFolders(
                List.of(new WorkspaceFolder(workspaceRoot.toUri().toString(), CLIENT.workspaceName)));
        init.setCapabilities(clientCapabilities());
        init.setClientInfo(new ClientInfo("nanocode", "0.2"));

        server.initialize(init).get(180, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());

        var cfg = new DidChangeConfigurationParams();
        cfg.setSettings(Map.of("java", Map.of("configuration", Map.of("updateBuildConfiguration", "automatic"))));
        server.getWorkspaceService().didChangeConfiguration(cfg);

        Thread listenThread = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                listen.get();
            } catch (Exception e) {
                if (JavaLspSupport.server != null)
                    System.err.println("JDT LS connection closed: " + e.getMessage());
            }
        });
        daemonThreads.add(listenThread);

        Runtime.getRuntime().addShutdownHook(new Thread(JavaLspSupport::stopQuiet, "nanocode-jdt-shutdown"));
        log.accept("Java LSP ready.");
    }

    public static void stopQuiet() {
        try {
            stop();
        } catch (Exception ignored) {
        }
    }

    public static void stop() throws Exception {
        DOC_VERSIONS.clear();
        DIAGNOSTICS.clear();
        LanguageServer s = server;
        server = null;
        if (s != null) {
            try {
                s.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("LSP shutdown error: " + e.getMessage());
            }
            try {
                s.exit();
            } catch (Exception e) {
                System.err.println("LSP exit error: " + e.getMessage());
            }
        }
        launcher = null;
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        // Interrupt tracked daemon threads
        synchronized (daemonThreads) {
            for (Thread t : daemonThreads) {
                t.interrupt();
            }
            daemonThreads.clear();
        }
    }

    public static void syncJavaFile(String pathRelativeOrAbsolute) throws IOException {
        if (!isActive())
            return;
        Path p = resolveWithinWorkspace(pathRelativeOrAbsolute);
        if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".java"))
            return;
        String uri = p.toUri().toString();
        String text = Files.readString(p);

        var v = DOC_VERSIONS.computeIfAbsent(uri, _ -> new AtomicInteger(0));
        if (v.get() == 0) {
            var item = new TextDocumentItem();
            item.setUri(uri);
            item.setLanguageId("java");
            item.setVersion(1);
            item.setText(text);
            var open = new DidOpenTextDocumentParams(item);
            server.getTextDocumentService().didOpen(open);
            v.set(1);
        } else {
            int next = v.incrementAndGet();
            var id = new VersionedTextDocumentIdentifier(uri, next);
            var ch = new DidChangeTextDocumentParams(id, List.of(new TextDocumentContentChangeEvent(text)));
            server.getTextDocumentService().didChange(ch);
        }
    }

    public static String definition(String pathStr, int line1, int col1) {
        if (!isActive())
            return "error: Java LSP not enabled";
        try {
            syncJavaFile(pathStr);
            Path p = resolveWithinWorkspace(pathStr);
            var id = new TextDocumentIdentifier(p.toUri().toString());
            var pos = new Position(line1 - 1, col1 - 1);
            var params = new DefinitionParams(id, pos);
            var res = server.getTextDocumentService().definition(params).get(10, TimeUnit.SECONDS);
            if (res == null)
                return "none";
            var sb = new StringBuilder();
            if (res.isLeft()) {
                for (Location loc : res.getLeft())
                    sb.append(formatLocation(loc)).append('\n');
            } else {
                for (LocationLink link : res.getRight())
                    sb.append(formatLocationLink(link)).append('\n');
            }
            var out = sb.toString().strip();
            return out.isEmpty() ? "none" : out;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public static String hover(String pathStr, int line1, int col1) {
        if (!isActive())
            return "error: Java LSP not enabled";
        try {
            syncJavaFile(pathStr);
            Path p = resolveWithinWorkspace(pathStr);
            var params = new HoverParams(new TextDocumentIdentifier(p.toUri().toString()),
                    new Position(line1 - 1, col1 - 1));
            Hover h = server.getTextDocumentService().hover(params).get(10, TimeUnit.SECONDS);
            if (h == null || h.getContents() == null)
                return "none";
            return hoverToString(h).strip();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    public static String diagnostics(String pathOpt) {
        if (!isActive())
            return "error: Java LSP not enabled";
        if (pathOpt == null || pathOpt.isBlank()) {
            if (DIAGNOSTICS.isEmpty())
                return "(no diagnostics yet)";
            var sb = new StringBuilder();
            for (var e : DIAGNOSTICS.entrySet()) {
                sb.append(e.getKey()).append('\n');
                for (Diagnostic d : e.getValue())
                    sb.append("  ").append(diagnosticLine(d)).append('\n');
            }
            return sb.toString().strip();
        }
        try {
            Path p = resolveWithinWorkspace(pathOpt);
            String uri = p.toUri().toString();
            List<Diagnostic> list = DIAGNOSTICS.getOrDefault(uri, List.of());
            if (list.isEmpty())
                return "(no diagnostics for this file)";
            var sb = new StringBuilder();
            for (Diagnostic d : list)
                sb.append(diagnosticLine(d)).append('\n');
            return sb.toString().strip();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String diagnosticLine(Diagnostic d) {
        var r = d.getRange();
        int l1 = r.getStart().getLine() + 1;
        int c1 = r.getStart().getCharacter() + 1;
        return "L%d:%d [%s] %s".formatted(l1, c1, d.getSeverity(), d.getMessage());
    }

    private static String hoverToString(Hover h) {
        Either<List<Either<String, MarkedString>>, org.eclipse.lsp4j.MarkupContent> either = h.getContents();
        if (either.isLeft()) {
            var sb = new StringBuilder();
            for (Either<String, MarkedString> part : either.getLeft()) {
                if (part.isLeft())
                    sb.append(part.getLeft());
                else
                    sb.append(part.getRight().getValue());
                sb.append('\n');
            }
            return sb.toString();
        }
        return either.getRight().getValue();
    }

    private static String formatLocation(Location loc) {
        var u = URI.create(loc.getUri());
        var path = Path.of(u).toString();
        var r = loc.getRange();
        return "%s L%d:%d".formatted(path, r.getStart().getLine() + 1, r.getStart().getCharacter() + 1);
    }

    private static String formatLocationLink(LocationLink link) {
        var u = URI.create(link.getTargetUri());
        var path = Path.of(u).toString();
        var r = link.getTargetRange();
        return "%s L%d:%d".formatted(path, r.getStart().getLine() + 1, r.getStart().getCharacter() + 1);
    }

    private static Path resolveWithinWorkspace(String pathStr) {
        Path p = Path.of(pathStr);
        Path abs = p.isAbsolute() ? p.normalize() : workspaceRoot.resolve(p).normalize();
        if (!abs.startsWith(workspaceRoot))
            throw new IllegalArgumentException("path outside workspace: " + pathStr);
        return abs;
    }

    private static void download(String url, Path dest, Consumer<String> log) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (res.statusCode() / 100 != 2)
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
        log.accept("Downloaded to " + dest);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root))
            return;
        try (var walk = Files.walk(root)) {
            var paths = walk.sorted(Collections.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.println("Failed to delete " + p + ": " + e.getMessage());
                }
            }
        }
    }

    private static void moveTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            var paths = walk.toList();
            for (Path src : paths) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        deleteRecursive(from);
    }

    private static Optional<Path> findJdtHomeWithPlugins(Path root) throws IOException {
        try (var walk = Files.walk(root, 6)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("plugins"))
                    .filter(Files::isDirectory)
                    .map(Path::getParent)
                    .filter(Objects::nonNull)
                    .findFirst();
        }
    }

    private static Optional<Path> findLauncherJar(Path jdtHome) throws IOException {
        Path plugins = jdtHome.resolve("plugins");
        if (!Files.isDirectory(plugins))
            return Optional.empty();
        try (var stream = Files.list(plugins)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .findFirst();
        }
    }

    private static String platformConfigDir(Path jdtHome) {
        String os = System.getProperty("os.name").toLowerCase();
        String sub = os.contains("win") ? "config_win" : os.contains("mac") ? "config_mac" : "config_linux";
        return jdtHome.resolve(sub).toString();
    }

    private static ClientCapabilities clientCapabilities() {
        var w = new WorkspaceClientCapabilities();
        w.setWorkspaceFolders(true);
        w.setApplyEdit(true);

        var sync = new SynchronizationCapabilities();
        sync.setDynamicRegistration(true);

        var td = new TextDocumentClientCapabilities();
        td.setSynchronization(sync);
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
        td.setDefinition(new DefinitionCapabilities(false));
        td.setHover(new HoverCapabilities(false));

        var caps = new ClientCapabilities();
        caps.setWorkspace(w);
        caps.setTextDocument(td);
        caps.setWindow(new WindowClientCapabilities());
        return caps;
    }

    private static void drain(java.io.InputStream err) {
        try (var br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                if (Boolean.parseBoolean(System.getenv().getOrDefault("NANOCODE_JDT_VERBOSE", "false")))
                    System.err.println("[jdtls] " + line);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.err.println("Error draining JDT stderr: " + e.getMessage());
            }
        }
    }

    private static final class JavaLanguageClient implements LanguageClient {
        volatile String workspaceUri;
        volatile String workspaceName;

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            DIAGNOSTICS.put(diagnostics.getUri(), List.copyOf(diagnostics.getDiagnostics()));
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
            return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
        }

        @Override
        public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
            return CompletableFuture.completedFuture(List.of(new WorkspaceFolder(workspaceUri, workspaceName)));
        }

        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
            int n = configurationParams.getItems().size();
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < n; i++)
                out.add(Map.of());
            return CompletableFuture.completedFuture(out);
        }

        /** JDT LS extensions — ignore; silences LSP4J "Unsupported notification" warnings. */
        @JsonNotification("language/status")
        @SuppressWarnings("unused")
        public void languageStatus(Object params) {
        }

        @JsonNotification("language/eventNotification")
        @SuppressWarnings("unused")
        public void languageEventNotification(Object params) {
        }
    }
}
