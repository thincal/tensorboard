// Copyright 2017 The TensorFlow Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.tensorflow.tensorboard.vulcanize;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.javascript.jscomp.BasicErrorManager;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DiagnosticGroup;
import com.google.javascript.jscomp.DiagnosticGroups;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.PropertyRenamingPolicy;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.WarningsGuard;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.protobuf.TextFormat;
import io.bazel.rules.closure.Webpath;
import io.bazel.rules.closure.webfiles.BuildInfo.Webfiles;
import io.bazel.rules.closure.webfiles.BuildInfo.WebfilesSource;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Html5Printer;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;

/** Simple one-off solution for TensorBoard vulcanization. */
public final class Vulcanize {

  private static final Pattern INLINE_SOURCE_MAP_PATTERN =
      Pattern.compile("//# sourceMappingURL=.*");

  private static final Pattern IGNORE_PATHS_PATTERN =
      Pattern.compile("/(?:polymer|marked-element)/.*");

  private static final ImmutableSet<String> EXTRA_JSDOC_TAGS =
      ImmutableSet.of("attribute", "hero", "group", "required");

  private static final Pattern SCRIPT_DELIMITER_PATTERN =
      Pattern.compile("//# sourceURL=build:/([^\n]+)");

  private static final String SCRIPT_DELIMITER = "//# sourceURL=build:/%name%";

  private static final Parser parser = Parser.htmlParser();
  private static final Map<Webpath, Path> webfiles = new HashMap<>();
  private static final Set<Webpath> alreadyInlined = new HashSet<>();
  private static final Set<String> legalese = new HashSet<>();
  private static final List<String> licenses = new ArrayList<>();
  private static final List<Webpath> stack = new ArrayList<>();
  private static final Map<String, SourceFile> externs = new LinkedHashMap<>();
  private static final List<SourceFile> sourcesFromJsLibraries = new ArrayList<>();
  private static final Map<Webpath, String> sourcesFromScriptTags = new LinkedHashMap<>();
  private static final Map<Webpath, Node> sourceTags = new LinkedHashMap<>();
  private static final Multimap<Webpath, String> suppressions = HashMultimap.create();
  private static CompilationLevel compilationLevel;
  private static Webpath outputPath;
  private static Node firstScript;
  private static Node licenseComment;
  private static int insideDemoSnippet;
  private static boolean testOnly;
  private static boolean wantsCompile;
  private static List<Pattern> ignoreRegExs = new ArrayList<>();

  // This is the default argument to Vulcanize for when the path_regexs_for_noinline attribute in
  // third_party/tensorboard/defs/vulcanize.bzl is not set.
  private static final String NO_NOINLINE_FILE_PROVIDED = "NO_REGEXS";

  private static final Pattern ABS_URI_PATTERN = Pattern.compile("^(?:/|[A-Za-z][A-Za-z0-9+.-]*:)");

  public static void main(String[] args) throws FileNotFoundException, IOException {
    compilationLevel = CompilationLevel.fromString(args[0]);
    wantsCompile = args[1].equals("true");
    testOnly = args[2].equals("true");
    Webpath inputPath = Webpath.get(args[3]);
    outputPath = Webpath.get(args[4]);
    Path output = Paths.get(args[5]);
    Path shasumOutput = Paths.get(args[6]);
    if (!args[7].equals(NO_NOINLINE_FILE_PROVIDED)) {
      String ignoreFile = new String(Files.readAllBytes(Paths.get(args[7])), UTF_8);
      Arrays.asList(ignoreFile.split("\n")).forEach(
          (str) -> ignoreRegExs.add(Pattern.compile(str)));
    }
    for (int i = 8; i < args.length; i++) {
      if (args[i].endsWith(".js")) {
        String code = new String(Files.readAllBytes(Paths.get(args[i])), UTF_8);
        SourceFile sourceFile = SourceFile.fromCode(args[i], code);
        if (code.contains("@externs")) {
          externs.put(args[i], sourceFile);
        } else {
          sourcesFromJsLibraries.add(sourceFile);
        }
        continue;
      }
      if (!args[i].endsWith(".pbtxt")) {
        continue;
      }
      Webfiles manifest = loadWebfilesPbtxt(Paths.get(args[i]));
      for (WebfilesSource src : manifest.getSrcList()) {
        webfiles.put(Webpath.get(src.getWebpath()), Paths.get(src.getPath()));
      }
    }
    stack.add(inputPath);
    Document document = parse(Files.readAllBytes(webfiles.get(inputPath)));
    transform(document);
    if (wantsCompile) {
      compile();
      combineScriptElements(document);
    } else if (firstScript != null) {
      firstScript.before(
          new Element(Tag.valueOf("script"), firstScript.baseUri())
              .appendChild(new DataNode("var CLOSURE_NO_DEPS = true;", firstScript.baseUri())));
      for (SourceFile source : sourcesFromJsLibraries) {
        String code = source.getCode();
        firstScript.before(
            new Element(Tag.valueOf("script"), firstScript.baseUri())
                .appendChild(new DataNode(code, firstScript.baseUri())));
      }
    }
    if (licenseComment != null) {
      licenseComment.attr("comment", String.format("\n%s\n", Joiner.on("\n\n").join(licenses)));
    }

    Files.write(
        output,
        Html5Printer.stringify(document).getBytes(UTF_8),
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);

    writeShasum(document, shasumOutput);
  }

  private static void transform(Node root) throws IOException {
    Node node = checkNotNull(root);
    Node newNode;
    while (true) {
      newNode = enterNode(node);
      if (node.equals(root)) {
        root = newNode;
      }
      node = newNode;
      if (node.childNodeSize() > 0) {
        node = node.childNode(0);
      } else {
        while (true) {
          newNode = leaveNode(node);
          if (node.equals(root)) {
            root = newNode;
          }
          node = newNode;
          if (node.equals(root)) {
            return;
          }
          Node next = node.nextSibling();
          if (next == null) {
            if (node.parentNode() == null) {
              return;
            }
            node = verifyNotNull(node.parentNode(), "unexpected root: %s", node);
          } else {
            node = next;
            break;
          }
        }
      }
    }
  }

  private static boolean isExternalCssNode(Node node) {
    if (node.nodeName().equals("link")
        && node.attr("rel").equals("stylesheet")
        && !node.attr("href").isEmpty()) {
      return true;
    }
    if (node.nodeName().equals("link")
        && node.attr("rel").equals("import")
        && (node.attr("type").equals("css")
            || node.attr("type").equals("text/css"))
        && !node.attr("href").isEmpty()) {
      return true;
    }
    return false;
  }

  private static Node enterNode(Node node) throws IOException {
    if (node.nodeName().equals("demo-snippet")) {
      insideDemoSnippet++;
    }
    if (insideDemoSnippet > 0) {
      return node;
    }
    if (node instanceof Element) {
      String href = node.attr("href");
      // Ignore any files that match any of the ignore regular expressions.
      boolean ignoreFile = false;
      for (Pattern pattern : ignoreRegExs) {
        if (pattern.matcher(href).find()) {
          ignoreFile = true;
          break;
        }
      }
      if (!ignoreFile) {
        if (isExternalCssNode(node)
            && !shouldIgnoreUri(href)) {
          node = visitStylesheet(node);
        } else if (node.nodeName().equals("link")
            && node.attr("rel").equals("import")) {
          // Inline HTML.
          node = visitHtmlImport(node);
        } else if (node.nodeName().equals("script")
            && !shouldIgnoreUri(node.attr("src"))
            && !node.hasAttr("jscomp-ignore")) {
          if (wantsCompile) {
            node = visitScript(node);
          } else {
            node = inlineScript(node);
          }
        }
      }
      rootifyAttribute(node, "href");
      rootifyAttribute(node, "src");
      rootifyAttribute(node, "action");
      rootifyAttribute(node, "assetpath");
    } else if (node instanceof Comment) {
      String text = ((Comment) node).getData();
      if (text.contains("@license")) {
        handleLicense(text);
        if (licenseComment == null) {
          licenseComment = node;
        } else {
          node = removeNode(node);
        }
      } else {
        node = removeNode(node);
      }
    }
    return node;
  }

  private static Node leaveNode(Node node) {
    if (node instanceof Document) {
      stack.remove(stack.size() - 1);
    } else if (node.nodeName().equals("demo-snippet")) {
      insideDemoSnippet--;
    }
    return node;
  }

  private static Node visitHtmlImport(Node node) throws IOException {
    Webpath href = me().lookup(Webpath.get(node.attr("href")));
    if (alreadyInlined.add(href)) {
      stack.add(href);
      Document subdocument = parse(Files.readAllBytes(getWebfile(href)));
      for (Attribute attr : node.attributes()) {
        subdocument.attr(attr.getKey(), attr.getValue());
      }
      return replaceNode(node, subdocument);
    } else {
      return removeNode(node);
    }
  }

  private static Node visitScript(Node node) throws IOException {
    Webpath path;
    String script;
    if (node.attr("src").isEmpty()) {
      path = makeSyntheticName(".js");
      script = getInlineScriptFromNode(node);
    } else {
      path = me().lookup(Webpath.get(node.attr("src")));
      script = new String(Files.readAllBytes(getWebfile(path)), UTF_8);
      script = INLINE_SOURCE_MAP_PATTERN.matcher(script).replaceAll("");
    }
    boolean wantsMinify = getAttrTransitive(node, "jscomp-minify").isPresent();

    if (node.hasAttr("jscomp-externs")) {
      String filePath = getWebfile(path).toString();
      SourceFile sourceFile = SourceFile.fromCode(filePath, script);
      externs.put(filePath, sourceFile);
      // Remove script tag of extern since it is not needed at the run time.
      return replaceNode(node, new TextNode("", node.baseUri()));
    } else if (node.attr("src").endsWith(".min.js")
        || getAttrTransitive(node, "jscomp-nocompile").isPresent()
        || wantsMinify) {
      if (wantsMinify) {
        script = minify(path, script);
      }
      Node newScript =
          new Element(Tag.valueOf("script"), node.baseUri(), node.attributes())
              .appendChild(new DataNode(script, node.baseUri()))
              .removeAttr("src")
              .removeAttr("jscomp-minify")
              .removeAttr("jscomp-nocompile");
      return replaceNode(node, newScript);
    } else {
      sourcesFromScriptTags.put(path, script);
      sourceTags.put(path, node);
      Optional<String> suppress = getAttrTransitive(node, "jscomp-suppress");
      if (suppress.isPresent()) {
        if (suppress.get().isEmpty()) {
          suppressions.put(path, "*");
        } else {
          suppressions.putAll(path, Splitter.on(' ').split(suppress.get()));
        }
      }
      return node;
    }
  }

  private static Node visitStylesheet(Node node) throws IOException {
    Webpath href = me().lookup(Webpath.get(node.attr("href")));
    return replaceNode(
        node,
        new Element(Tag.valueOf("style"), node.baseUri(), node.attributes())
            .appendChild(
                new DataNode(
                    new String(Files.readAllBytes(getWebfile(href)), UTF_8), node.baseUri()))
            .removeAttr("rel")
            .removeAttr("href"));
  }

  private static Node inlineScript(Node node) throws IOException {
    Node result;
    if (node.attr("src").isEmpty()) {
      result = node;
    } else {
      Webpath href = me().lookup(Webpath.get(node.attr("src")));
      String code = new String(Files.readAllBytes(getWebfile(href)), UTF_8);
      code = code.replace("</script>", "</JAVA_SCRIIIIPT/>");
      code = INLINE_SOURCE_MAP_PATTERN.matcher(code).replaceAll("");
      result = replaceNode(
          node,
          new Element(Tag.valueOf("script"), node.baseUri(), node.attributes())
              .appendChild(new DataNode(code, node.baseUri()))
              .removeAttr("src"));
    }
    if (firstScript == null) {
      firstScript = result;
    }
    return result;
  }

  private static Optional<String> getAttrTransitive(Node node, String attr) {
    while (node != null) {
      if (node.hasAttr(attr)) {
        return Optional.of(node.attr(attr));
      }
      node = node.parent();
    }
    return Optional.absent();
  }

  private static Node replaceNode(Node oldNode, Node newNode) {
    oldNode.replaceWith(newNode);
    return newNode;
  }

  private static Node removeNode(Node node) {
    return replaceNode(node, new TextNode("", node.baseUri()));
  }

  private static Path getWebfile(Webpath path) {
    return verifyNotNull(webfiles.get(path), "Bad ref: %s -> %s", me(), path);
  }

  private static void compile() {
    if (sourcesFromScriptTags.isEmpty()) {
      return;
    }

    CompilerOptions options = new CompilerOptions();
    compilationLevel.setOptionsForCompilationLevel(options);
    options.setModuleResolutionMode(ModuleLoader.ResolutionMode.NODE);

    // Nice options.
    options.setColorizeErrorOutput(true);
    options.setContinueAfterErrors(true);
    options.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2018);
    options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT_2015);
    options.setGenerateExports(true);
    options.setStrictModeInput(false);
    options.setExtraAnnotationNames(EXTRA_JSDOC_TAGS);

    // Strict mode gets in the way of concatenating all script tags as script
    // tags assume different modes. Disable global strict imposed by JSComp.
    // A function can still have a "use strict" inside.
    options.setEmitUseStrict(false);

    // So we can chop JS binary back up into the original script tags.
    options.setPrintInputDelimiter(true);
    options.setInputDelimiter(SCRIPT_DELIMITER);

    // Optimizations that are too advanced for us right now.
    options.setPropertyRenaming(PropertyRenamingPolicy.OFF);
    options.setCheckGlobalThisLevel(CheckLevel.OFF);
    options.setRemoveUnusedPrototypeProperties(false);
    options.setRemoveUnusedPrototypePropertiesInExterns(false);
    options.setRemoveUnusedClassProperties(false);
    // Prevent Polymer bound method (invisible to JSComp) to be over-optimized.
    options.setInlineFunctions(CompilerOptions.Reach.NONE);
    options.setDevirtualizeMethods(false);

    // Dependency management.
    options.setClosurePass(true);
    // TODO turn dependency pruning back on. ES6 modules are currently (incorrectly) considered
    // moochers. Once the compiler no longer considers them moochers the dependencies will be in an
    // incorrect order. The compiler will put moochers first, then other explicit entry points
    // (if something is both an entry point and a moocher it goes first). vz-example-viewer.ts
    // generates an ES6 module JS, and it will soon no longer be considered a moocher and be moved
    // after its use in some moochers. To reenable dependency pruning, either the TS generation
    // should not generate an ES6 module (a file with just goog.requires is a moocher!),
    // or vz-example-viewer should be explicitly imported by the code that uses it. Alternatively,
    // we could ensure that the input order to the compiler is correct and all inputs are used, and
    // turn off both sorting and pruning.
    options.setDependencyOptions(com.google.javascript.jscomp.DependencyOptions.sortOnly());

    // Polymer pass.
    options.setPolymerVersion(2);

    // Debug flags.
    if (testOnly) {
      options.setPrettyPrint(true);
      options.setGeneratePseudoNames(true);
      options.setExportTestFunctions(true);
    }

    // Don't print warnings from <script jscomp-suppress="group1 group2" ...> tags.
    ImmutableMultimap<DiagnosticType, String> diagnosticGroups = initDiagnosticGroups();
    options.addWarningsGuard(
        new WarningsGuard() {
          @Override
          public CheckLevel level(JSError error) {
            if (error.getSourceName() == null) {
              return null;
            }
            if (error.getDefaultLevel() == CheckLevel.WARNING
                && isErrorFromTranspiledTypescriptCode(error)) {
              // Let's put our faith in the TypeScript compiler. At least until we have tsickle as
              // part of our build process.
              return CheckLevel.OFF;
            }
            if (error.getDefaultLevel() == CheckLevel.WARNING
                && (error.getSourceName().startsWith("/iron-")
                    || error.getSourceName().startsWith("/neon-")
                    || error.getSourceName().startsWith("/paper-"))) {
              // Suppress warnings in the Polymer standard libraries.
              return CheckLevel.OFF;
            }
            if (error.getSourceName().startsWith("javascript/externs")
                || error.getSourceName().contains("com_google_javascript_closure_compiler_externs")) {
              // TODO(@jart): Figure out why these "mismatch of the removeEventListener property on
              //             type" warnings are showing up.
              //             https://github.com/google/closure-compiler/pull/1959
              return CheckLevel.OFF;
            }
            if (error.getSourceName().endsWith("externs/webcomponents-externs.js")) {
              // TODO(stephanwlee): Figure out why above externs cause variable
              // declare issue. Seems to do with usage of `let` in Polymer 2.x
              // branch.
              // Ref: #2425.
              return CheckLevel.WARNING;
            }
            if (IGNORE_PATHS_PATTERN.matcher(error.getSourceName()).matches()) {
              return CheckLevel.OFF;
            }
            if ((error.getSourceName().startsWith("/tf-") || error.getSourceName().startsWith("/vz-"))
                && error.getType().key.equals("JSC_VAR_MULTIPLY_DECLARED_ERROR")) {
              return CheckLevel.OFF; // TODO(@jart): Remove when tf/vz components/plugins are ES6 modules.
            }
            if (error.getType().key.equals("JSC_POLYMER_UNQUALIFIED_BEHAVIOR")
                || error.getType().key.equals("JSC_POLYMER_UNANNOTATED_BEHAVIOR")) {
              return CheckLevel.OFF; // TODO(@jart): What is wrong with this thing?
            }
            Collection<String> codes = suppressions.get(Webpath.get(error.getSourceName()));
            if (codes.contains("*") || codes.contains(error.getType().key)) {
              return CheckLevel.OFF;
            }
            for (String group : diagnosticGroups.get(error.getType())) {
              if (codes.contains(group)) {
                return CheckLevel.OFF;
              }
            }
            return null;
          }
        });

    // Get reverse topological script tags and their web paths, which js_library stuff first.
    List<SourceFile> sauce = Lists.newArrayList(sourcesFromJsLibraries);
    for (Map.Entry<Webpath, String> source : sourcesFromScriptTags.entrySet()) {
      sauce.add(SourceFile.fromCode(source.getKey().toString(), source.getValue()));
    }

    List<SourceFile> externsList = new ArrayList<>(externs.values());

    // Compile everything into a single script.
    Compiler compiler = new Compiler();
    compiler.disableThreads();
    Result result = compiler.compile(externsList, sauce, options);
    if (!result.success) {
      System.exit(1);
    }
    String jsBlob = compiler.toSource();
    // Split apart the JS blob and put it back in the original <script> locations.
    Deque<Map.Entry<Webpath, Node>> tags = new ArrayDeque<>();
    tags.addAll(sourceTags.entrySet());
    Matcher matcher = SCRIPT_DELIMITER_PATTERN.matcher(jsBlob);
    verify(matcher.find(), "Nothing found in compiled JS blob!");
    Webpath path = Webpath.get(matcher.group(1));
    int start = 0;
    while (matcher.find()) {
      if (sourceTags.containsKey(path)) {
        swapScript(tags, path, jsBlob.substring(start, matcher.start()));
        start = matcher.start();
      }
      path = Webpath.get(matcher.group(1));
    }
    swapScript(tags, path, jsBlob.substring(start));
    verify(tags.isEmpty(), "<script> wasn't compiled: %s", tags);
  }

  private static boolean isErrorFromTranspiledTypescriptCode(JSError error) {
    // We perform this check by looking for a concomitant .d.ts webfile which is generated by the
    // TypeScript compiler. Ideally we would use SourceExcerptProvider to determine the original
    // source name, but WarningsGuard objects do not appear to have access to that.
    String path = error.getSourceName();
    if (!path.endsWith(".js")) {
      return false;
    }
    return webfiles.containsKey(Webpath.get(path.substring(0, path.length() - 3) + ".d.ts"));
  }

  private static void swapScript(
      Deque<Map.Entry<Webpath, Node>> tags, Webpath path, String script) {
    verify(!tags.isEmpty(), "jscomp compiled %s after last <script>?!", path);
    Webpath want = tags.getFirst().getKey();
    verify(path.equals(want), "<script> tag for %s should come before %s", path, want);
    Node tag = tags.removeFirst().getValue();
    tag.replaceWith(
        new Element(Tag.valueOf("script"), tag.baseUri())
            .appendChild(new DataNode(script, tag.baseUri())));
  }

  private static String minify(Webpath path, String script) {
    CompilerOptions options = new CompilerOptions();
    options.skipAllCompilerPasses();
    options.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_2016);
    options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT5);
    options.setContinueAfterErrors(true);
    CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
    if (testOnly) {
      options.setPrettyPrint(true);
      options.setGeneratePseudoNames(true);
    }
    Compiler compiler = new Compiler(new JsPrintlessErrorManager());
    compiler.disableThreads();
    compiler.compile(
        ImmutableList.of(),
        ImmutableList.of(SourceFile.fromCode(path.toString(), script)),
        options);
    return compiler.toSource();
  }

  private static void handleLicense(String text) {
    if (legalese.add(CharMatcher.whitespace().removeFrom(text))) {
      licenses.add(CharMatcher.anyOf("\r\n").trimFrom(text));
    }
  }

  private static Webpath me() {
    return Iterables.getLast(stack);
  }

  private static Webpath makeSyntheticName(String extension) {
    String me = me().toString();
    Webpath result = Webpath.get(me + extension);
    int n = 2;
    while (sourcesFromScriptTags.containsKey(result)) {
      result = Webpath.get(String.format("%s-%d%s", me, n++, extension));
    }
    return result;
  }

  private static void rootifyAttribute(Node node, String attribute) {
    String value = node.attr(attribute);
    if (value.isEmpty()) {
      return;
    }
    Webpath uri = Webpath.get(value);
    // Form absolute path from uri if uri is not an absolute path.
    // Note that webfiles is a map of absolute webpaths to relative filepaths.
    Webpath absUri = isAbsolutePath(uri)
        ? uri : me().getParent().resolve(uri).normalize();

    if (webfiles.containsKey(absUri)) {
      node.attr(attribute, outputPath.getParent().relativize(absUri).toString());
    }
  }

  /**
   * Checks whether a path is a absolute path.
   * Webpath.isAbsolute does not take data uri and other forms of absolute path into account.
   */
  private static Boolean isAbsolutePath(Webpath path) {
    return path.isAbsolute() || ABS_URI_PATTERN.matcher(path.toString()).find();
  }

  private static String getInlineScriptFromNode(Node node) {
    StringBuilder sb = new StringBuilder();
    for (Node child : node.childNodes()) {
      if (child instanceof DataNode) {
        sb.append(((DataNode) child).getWholeData());
      }
    }
    return sb.toString();
  }

  private static Document parse(byte[] bytes) {
    return parse(new ByteArrayInputStream(bytes));
  }

  private static Document parse(InputStream input) {
    Document document;
    try {
      document = Jsoup.parse(input, null, "", parser);
    } catch (IOException e) {
      throw new AssertionError("I/O error when parsing byte array D:", e);
    }
    document.outputSettings().indentAmount(0);
    document.outputSettings().prettyPrint(false);
    return document;
  }

  private static Webfiles loadWebfilesPbtxt(Path path) throws IOException {
    verify(path.toString().endsWith(".pbtxt"), "Not a pbtxt file: %s", path);
    Webfiles.Builder build = Webfiles.newBuilder();
    TextFormat.getParser().merge(new String(Files.readAllBytes(path), UTF_8), build);
    return build.build();
  }

  private static boolean shouldIgnoreUri(String uri) {
    return uri.startsWith("#")
        || uri.endsWith("/")
        || uri.contains("//")
        || uri.startsWith("data:")
        || uri.startsWith("javascript:")
        || uri.startsWith("mailto:")
        // The following are intended to filter out URLs with Polymer variables.
        || (uri.contains("[[") && uri.contains("]]"))
        || (uri.contains("{{") && uri.contains("}}"));
  }

  private static ImmutableMultimap<DiagnosticType, String> initDiagnosticGroups() {
    DiagnosticGroups groups = new DiagnosticGroups();
    Multimap<DiagnosticType, String> builder = HashMultimap.create();
    for (Map.Entry<String, DiagnosticGroup> group : groups.getRegisteredGroups().entrySet()) {
      for (DiagnosticType type : group.getValue().getTypes()) {
        builder.put(type, group.getKey());
      }
    }
    return ImmutableMultimap.copyOf(builder);
  }

  // Combine content of script tags into a group. To guarantee the correctness, it only groups
  // content of `src`-less scripts between `src`-full scripts. The last combination gets inserted at the
  // end of the document.
  // e.g.,
  //   <script>A</script>
  //   <script>B</script>
  //   <script src="srcful1"></script>
  //   <script src="srcful2"></script>
  //   <script>C</script>
  //   <script>D</script>
  //   <script src="srcful3"></script>
  //   <script>E</script>
  // gets compiled as
  //   <script>A,B</script>
  //   <script src="srcful1"></script>
  //   <script src="srcful2"></script>
  //   <script>C,D</script>
  //   <script src="srcful3"></script>
  //   <script>E</script>
  private static void combineScriptElements(Document document) {
    Elements scripts = document.getElementsByTag("script");
    StringBuilder sourcesBuilder = new StringBuilder();

    for (Element script : scripts) {
      if (!script.attr("src").isEmpty()) {
        if (sourcesBuilder.length() == 0) {
          continue;
        }
        Element scriptTag = new Element(Tag.valueOf("script"), "")
            .appendChild(new DataNode(sourcesBuilder.toString(), ""));
        script.before(scriptTag);
        sourcesBuilder = new StringBuilder();
      } else {
        sourcesBuilder.append(script.html()).append("\n");
        script.remove();
      }
    }

    // jsoup parser creates body elements for each HTML files. Since document.body() returns the
    // first instance and we want to insert the script element at the end of the document, we
    // manually grab the last one.
    Element lastBody = Iterables.getLast(document.getElementsByTag("body"));

    Element scriptTag = new Element(Tag.valueOf("script"), "")
        .appendChild(new DataNode(sourcesBuilder.toString(), ""));
    lastBody.appendChild(scriptTag);
  }

  private static ArrayList<String> computeScriptShasum(Document document) throws FileNotFoundException, IOException {
    ArrayList<String> hashes = new ArrayList<>();
    for (Element script : document.getElementsByTag("script")) {
      String src = script.attr("src");
      String sourceContent;
      if (src.isEmpty()) {
        sourceContent = script.html();
      } else {
        // script element that remains are the ones with src that is absolute or annotated with
        // `jscomp-ignore`. They must resolve from the root because those srcs are rootified.
        Webpath webpathSrc = Webpath.get(src);
        Webpath webpath = Webpath.get("/").resolve(Webpath.get(src)).normalize();
        if (isAbsolutePath(webpathSrc)) {
          System.err.println(
              "WARNING: "
                  + webpathSrc
                  + " refers to a remote resource. Please add it to CSP manually. Detail: "
                  + script.outerHtml());
          continue;
        } else if (!webfiles.containsKey(webpath)) {
          throw new FileNotFoundException(
              "Expected webfiles for " + webpath + " to exist. Related: " + script.outerHtml());
        }
        sourceContent = new String(Files.readAllBytes(webfiles.get(webpath)), UTF_8);
      }
      String hash = BaseEncoding.base64().encode(
          Hashing.sha256().hashString(sourceContent, UTF_8).asBytes());
      hashes.add(hash);
    }
    return hashes;
  }

  // Writes sha256 of script tags in base64 in the document.
  private static void writeShasum(Document document, Path output) throws FileNotFoundException, IOException {
    String hashes = Joiner.on("\n").join(computeScriptShasum(document));
    Files.write(
        output,
        hashes.getBytes(UTF_8),
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static final class JsPrintlessErrorManager extends BasicErrorManager {

    @Override
    public void println(CheckLevel level, JSError error) {}

    @Override
    public void printSummary() {}
  }
}
