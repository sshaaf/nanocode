///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0
//SOURCES JavaLspSupport.java

import module java.base;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Non-interactive smoke test: same {@link JavaLspSupport} nanocode uses, no LLM.
 * Usage: jbang LspProbe.java [workspace-root]
 */
public class LspProbe {

    public static void main(String[] args) throws Exception {
        Path ws = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        System.err.println("Workspace: " + ws);
        JavaLspSupport.installIfNeeded(m -> System.err.println("[install] " + m));
        JavaLspSupport.start(ws, m -> System.err.println("[jdt] " + m));

        int waitSec = Integer.parseInt(System.getenv().getOrDefault("LSP_PROBE_SYNC_SEC", "15"));
        System.err.println("[jdt] Waiting for project sync (" + waitSec + "s)…");
        Thread.sleep(waitSec * 1000L);

        String testFile = "JavaLspSupport.java";
        record Case(String name, String result) {
        }
        var out = new ArrayList<Case>();
        out.add(new Case("java_diagnostics — " + testFile,
                JavaLspSupport.diagnostics(testFile)));
        out.add(new Case("java_hover — HttpClient at JavaLspSupport.java L78 C30",
                JavaLspSupport.hover(testFile, 78, 30)));
        out.add(new Case("java_definition — Path at JavaLspSupport.java L93 C16",
                JavaLspSupport.definition(testFile, 93, 16)));

        System.out.println("=== LSP probe results ===");
        for (Case c : out) {
            System.out.println();
            System.out.println("--- " + c.name + " ---");
            System.out.println(c.result == null || c.result.isBlank() ? "(empty)" : c.result);
        }

        JavaLspSupport.stopQuiet();
    }
}
