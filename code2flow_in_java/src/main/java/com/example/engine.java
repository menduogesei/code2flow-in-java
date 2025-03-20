package com.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class engine {
    public static void main(String[] args) {
        // 初始化参数默认值
        List<String> sources = new ArrayList<>();
        String output = "out.png";
        String language = null;
        String targetFunction = null;
        int upstreamDepth = 0;
        int downstreamDepth = 0;
        List<String> excludeFunctions = new ArrayList<>();
        List<String> excludeNamespaces = new ArrayList<>();
        List<String> includeOnlyFunctions = new ArrayList<>();
        List<String> includeOnlyNamespaces = new ArrayList<>();
        boolean noGrouping = false;
        boolean noTrimming = false;
        boolean hideLegend = false;
        boolean skipParseErrors = false;
        String sourceType = "script";
        String rubyVersion = "27";
        boolean quiet = false;
        boolean verbose = false;
        final String VERSION = "2.0.0"; // 示例版本号

        try {
            // 手动解析命令行参数
            for (int i = 0; i < args.length; ) {
                String arg = args[i];
                switch (arg) {
                    case "--output":
                    case "-o":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        output = args[++i];
                        break;
                    case "--language":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        language = args[++i];
                        break;
                    case "--target-function":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        targetFunction = args[++i];
                        break;
                    case "--upstream-depth":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        upstreamDepth = Integer.parseInt(args[++i]);
                        break;
                    case "--downstream-depth":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        downstreamDepth = Integer.parseInt(args[++i]);
                        break;
                    case "--exclude-functions":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        excludeFunctions = Arrays.asList(args[++i].split(","));
                        break;
                    case "--exclude-namespaces":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        excludeNamespaces = Arrays.asList(args[++i].split(","));
                        break;
                    case "--include-only-functions":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        includeOnlyFunctions = Arrays.asList(args[++i].split(","));
                        break;
                    case "--include-only-namespaces":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        includeOnlyNamespaces = Arrays.asList(args[++i].split(","));
                        break;
                    case "--no-grouping":
                        noGrouping = true;
                        break;
                    case "--no-trimming":
                        noTrimming = true;
                        break;
                    case "--hide-legend":
                        hideLegend = true;
                        break;
                    case "--skip-parse-errors":
                        skipParseErrors = true;
                        break;
                    case "--source-type":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        sourceType = args[++i];
                        break;
                    case "--ruby-version":
                        if (i+1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        rubyVersion = args[++i];
                        break;
                    case "--quiet":
                    case "-q":
                        quiet = true;
                        break;
                    case "--verbose":
                    case "-v":
                        verbose = true;
                        break;
                    case "--version":
                        System.out.println("code2flow " + VERSION);
                        System.exit(0);
                    default:
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        } else {
                            sources.add(arg);
                        }
                }
                i++; // 移动到下一个参数
            }

            // 验证互斥参数
            if (quiet && verbose) {
                throw new IllegalArgumentException("Cannot use both --quiet and --verbose");
            }

            // 构建参数对象
            LanguageParams langParams = new LanguageParams(sourceType, rubyVersion);
            SubsetParams subsetParams = SubsetParams.generate(
                    targetFunction, upstreamDepth, downstreamDepth);

            // 调用核心方法
            code2flow(
                    sources,
                    output,
                    language,
                    hideLegend,
                    excludeNamespaces,
                    excludeFunctions,
                    includeOnlyNamespaces,
                    includeOnlyFunctions,
                    noGrouping,
                    noTrimming,
                    skipParseErrors,
                    langParams,
                    subsetParams
            );

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Usage: java engine [options] sources...");
            System.err.println("Options:");
            System.err.println("  --output, -o <file>     Output file (default: out.png)");
            System.err.println("  --language <lang>       Specify source language");
            System.err.println("  --target-function <func> Focus on specific function");
            // ... 其他选项帮助信息
            System.exit(1);
        }
    }
    public static Node findTargetNode(SubsetParams subsetParams, List<Node> allNodes) {
        List<Node> targetNodes = new ArrayList<>();

        for (Node node : allNodes) {
            boolean matches = node.getToken().equals(subsetParams.targetFunction) ||
                    node.token_with_ownership().equals(subsetParams.targetFunction) ||
                    node.name().equals(subsetParams.targetFunction);

            if (matches) {
                targetNodes.add(node);
            }
        }

        if (targetNodes.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Could not find node '%s' to build a subset",
                    subsetParams.targetFunction));
        }

        if (targetNodes.size() > 1) {
            throw new IllegalArgumentException(String.format(
                    "Found multiple nodes for '%s': %s. Try either a `class.func` or `filename::class.func`",
                    subsetParams.targetFunction,
                    targetNodes.stream().map(Node::toString).collect(Collectors.joining(", "))));
        }

        return targetNodes.get(0);
    }
    private static List<Group> limitNamespaces(
            List<Group> fileGroups,
            List<String> excludeNamespaces,
            List<String> includeOnlyNamespaces) {

        Set<String> removedNamespaces = new HashSet<>();
        List<Group> groupsToProcess = new ArrayList<>(fileGroups);

        // 第一层处理：文件组级别的过滤
        for (Group group : groupsToProcess) {
            String groupToken = group.getToken();

            // 处理排除逻辑
            if (excludeNamespaces.contains(groupToken)) {
                removeAllNodes(group);
                removedNamespaces.add(groupToken);
            }

            // 处理包含逻辑
            if (!includeOnlyNamespaces.isEmpty() &&
                    !includeOnlyNamespaces.contains(groupToken)) {
                removeAllNodes(group);
                removedNamespaces.add(groupToken);
            }

            // 第二层处理：子组级别的过滤
            for (Group subgroup : group.subgroups) {
                String subToken = subgroup.getToken();
                boolean parentIncluded = isAnyParentIncluded(subgroup, includeOnlyNamespaces);

                // 子组排除逻辑
                if (excludeNamespaces.contains(subToken)) {
                    removeAllNodes(subgroup);
                    removedNamespaces.add(subToken);
                }

                // 子组包含逻辑
                if (!includeOnlyNamespaces.isEmpty() &&
                        !includeOnlyNamespaces.contains(subToken) &&
                        !parentIncluded) {
                    removeAllNodes(subgroup);
                    removedNamespaces.add(subToken);
                }
            }
        }

        // 处理未找到的命名空间警告
        for (String ns : excludeNamespaces) {
            if (!removedNamespaces.contains(ns)) {
                System.out.println("[WARNING] Could not exclude namespace '" + ns
                        + "' because it was not found.");
            }
        }

        return fileGroups.stream()
                .filter(g -> !g.all_nodes().isEmpty())
                .collect(Collectors.toList());
    }

    // 辅助方法：移除组内所有节点
    private static void removeAllNodes(Group group) {
        new ArrayList<>(group.all_nodes()).forEach(Node::remove_from_parent);
    }

    // 辅助方法：检查父组是否在包含列表中
    private static boolean isAnyParentIncluded(Group subgroup, List<String> includeList) {
        if (includeList.isEmpty()) return true;

        Group current = subgroup;
        while (current != null) {
            if (includeList.contains(current.getToken())) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }
    public static List<CallLinker.FindLinkResult> findLinks(Node nodeA, List<Node> allNodes) {
        return nodeA.calls.stream()
                .map(call -> {
                    CallLinker.FindLinkResult result = CallLinker.findLinkForCall(call, nodeA, allNodes);

                    return result;
                })
                .filter(result -> result.matchedNode != null || result.unresolvedCall != null)
                .collect(Collectors.toList());
    }
    private static List<Group> limitFunctions(
            List<Group> fileGroups,
            List<String> excludeFunctions,
            List<String> includeOnlyFunctions) {

        Set<String> removedFuncs = new HashSet<>();

        // 遍历所有文件组的副本（防止并发修改）
        new ArrayList<>(fileGroups).forEach(group -> {
            // 遍历组内所有节点的副本（防止删除时的并发问题）
            new ArrayList<>(group.all_nodes()).forEach(node -> {
                String token = node.getToken();

                // 判断是否需要移除
                boolean shouldRemove = excludeFunctions.contains(token) ||
                        (!includeOnlyFunctions.isEmpty() &&
                                !includeOnlyFunctions.contains(token));

                if (shouldRemove) {
                    node.remove_from_parent();
                    removedFuncs.add(token);
                }
            });
        });

        // 生成未找到的警告
        excludeFunctions.stream()
                .filter(f -> !removedFuncs.contains(f))
                .forEach(f -> System.out.println("Warning: Could not exclude function '" + f + "'"));

        return fileGroups.stream()
                .filter(g -> !g.all_nodes().isEmpty())
                .collect(Collectors.toList());
    }
    public static void generateGraphviz(File outputFile, String extension, File finalImgFilename) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        System.out.println("Running graphviz to make the image...");

        // 构建命令参数
        ProcessBuilder pb = new ProcessBuilder(
                "dot",
                "-T" + extension,
                outputFile.getAbsolutePath()
        );

        // 设置输出文件
        pb.redirectOutput(finalImgFilename);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean success = process.waitFor(30, TimeUnit.SECONDS); // 设置30秒超时

            if (!success) {
                System.err.println("Graphviz execution timed out");
                process.destroyForcibly();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                System.err.printf("*** Graphviz returned non-zero exit code (%d)! Try running manually: %s -v -O ​***%n",
                        exitCode, String.join(" ", pb.command()));
            }

            double duration = (System.currentTimeMillis() - startTime) / 1000.0;
            System.out.printf("Graphviz finished in %.2f seconds.%n", duration);

        } catch (IOException ex) {
            System.err.println("Failed to execute Graphviz: " + ex.getMessage());
            throw ex;
        }
    }
    private static final Set<String> VALID_EXTENSIONS = new HashSet<>(Arrays.asList("gv", "json", "png", "svg"));
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList("png", "svg"));
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList("gv", "json"));

    public static void code2flow(
            List<String> rawSourcePaths,
            String outputFile,
            String language,
            boolean hideLegend,
            List<String> excludeNamespaces,
            List<String> excludeFunctions,
            List<String> includeOnlyNamespaces,
            List<String> includeOnlyFunctions,
            boolean noGrouping,
            boolean noTrimming,
            boolean skipParseErrors,
            LanguageParams langParams,
            SubsetParams subsetParams) throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();

        // 参数初始化
        if (excludeNamespaces == null) excludeNamespaces = Collections.emptyList();
        if (excludeFunctions == null) excludeFunctions = Collections.emptyList();
        if (includeOnlyNamespaces == null) includeOnlyNamespaces = Collections.emptyList();
        if (includeOnlyFunctions == null) includeOnlyFunctions = Collections.emptyList();
        if (langParams == null) langParams = new LanguageParams();

        // 输出文件验证
        String outputExt = null;
        if (outputFile.contains(".")) {
            outputExt = outputFile.substring(outputFile.lastIndexOf('.') + 1);
            if (!VALID_EXTENSIONS.contains(outputExt)) {
                throw new IllegalArgumentException("Invalid output extension. Valid: " + VALID_EXTENSIONS);
            }
        }

        // 图像文件处理
        String finalImgFilename = null;
        String extension = null;
        if (outputExt != null && IMAGE_EXTENSIONS.contains(outputExt)) {
            if (!isGraphvizInstalled()) {
                throw new IllegalStateException("Graphviz (dot) not found. Install or use text formats: " + TEXT_EXTENSIONS);
            }
            finalImgFilename = outputFile;
            String baseName = outputFile.substring(0, outputFile.lastIndexOf('.'));
            outputFile = baseName + ".gv";
            extension = outputExt;
        }

        // 获取源文件和语言
        Object[] sourcesAndLang = getSourcesAndLanguage(rawSourcePaths, language);
        List<String> sources = (List<String>) sourcesAndLang[0];
        String detectedLang = (String) sourcesAndLang[1];

        // 核心处理流程
        SubsetResult result = mapIt(
                sources, detectedLang, noTrimming,
                excludeNamespaces, excludeFunctions,
                includeOnlyNamespaces, includeOnlyFunctions,
                skipParseErrors, langParams);

        // 子集过滤
        if (subsetParams != null) {
            result = engine.filterForSubset(subsetParams, result.nodes, result.edges, result.fileGroups);
        }

        // 排序
        Collections.sort(result.fileGroups, Comparator.comparing(Group::getToken));
        Collections.sort(result.nodes, Comparator.comparing(Node::token_with_ownership));
        Collections.sort(result.edges, Comparator.comparing(e -> e.node0.getToken() + "->" + e.node1.getToken()));

        // 生成输出文件
        try (Writer writer = new FileWriter(outputFile)) {
            writeFile(writer, result.nodes, result.edges, result.fileGroups,
                    hideLegend, noGrouping, outputExt != null && outputExt.equals("json"));
        }

        System.out.printf("Wrote output file %s with %d nodes and %d edges.%n",
                outputFile, result.nodes.size(), result.edges.size());

        // 生成最终图像
        if (finalImgFilename != null) {
            generateFinalImage(new File(outputFile), extension, new File(finalImgFilename), result.edges.size());
        }

        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.printf("Code2flow finished processing in %.2f seconds.%n", duration);
    }

    // 辅助方法
    private static boolean isGraphvizInstalled() {
        try {
            Process p = new ProcessBuilder("dot", "-V").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // 方法重载简化调用
    public static void code2flow(List<String> sources, String outputFile) throws IOException, InterruptedException {
        code2flow(sources, outputFile, null, true,
                null, null, null, null,
                false, false, false,
                new LanguageParams(), null);
    }
    public static void generateFinalImage(
            File outputFile,
            String extension,
            File finalImgFile,
            // 参数保留但未使用（与原始Python代码行为一致）
            @SuppressWarnings("unused") int numEdges) throws IOException, InterruptedException {

        // 调用之前转换的Graphviz生成方法
        generateGraphviz(outputFile, extension, finalImgFile);

        // 输出完成信息（保持与Python相同的日志级别）
        System.out.printf("Completed your flowchart! To see it, open '%s'.%n",
                finalImgFile.getAbsolutePath());
    }
    public static Set<Node> filterNodesForSubset(SubsetParams subsetParams, List<Node> allNodes, List<Edge> edges) {
        Node targetNode = findTargetNode(subsetParams, allNodes);


        Map<Node, Set<Node>> downstreamMap = new HashMap<>();
        Map<Node, Set<Node>> upstreamMap = new HashMap<>();
        for (Edge edge : edges) {
            // 正确类型推导的写法
            downstreamMap.computeIfAbsent(edge.node0, k -> new HashSet<>()).add(edge.node1);

            upstreamMap.computeIfAbsent(edge.node1, k -> new HashSet<>()).add(edge.node0);
        }

        Set<Node> includedNodes = new LinkedHashSet<>();
        includedNodes.add(targetNode);

        traverseDependencies(
                includedNodes,
                targetNode,
                downstreamMap,
                subsetParams.downstreamDepth,
                false
        );

        traverseDependencies(
                includedNodes,
                targetNode,
                upstreamMap,
                subsetParams.upstreamDepth,
                true
        );

        return includedNodes;
    }
    public static List<Edge> filterEdgesForSubset(Set<Node> newNodes, List<Edge> edges) {
        List<Edge> filteredEdges = new ArrayList<>();

        for (Edge edge : edges) {
            if (newNodes.contains(edge.node0) && newNodes.contains(edge.node1)) {
                filteredEdges.add(edge);
            }
        }
        return filteredEdges;
    }
    public static List<Group> filterGroupsForSubset(Set<Node> newNodes, List<Group> fileGroups) {
        // 第一步：从所有组中移除未包含的节点
        for (Group fileGroup : fileGroups) {
            List<Node> toRemove = new ArrayList<>();
            for (Node node : fileGroup.all_nodes()) {
                if (!newNodes.contains(node)) {
                    toRemove.add(node);
                }
            }
            toRemove.forEach(Node::remove_from_parent);
        }

        // 第二步：过滤空文件组（使用新集合避免并发修改）
        List<Group> newFileGroups = fileGroups.stream()
                .filter(g -> !g.all_nodes().isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));

        // 第三步：深度清理空子组
        newFileGroups.forEach(fileGroup -> {
            // 使用广度优先搜索处理嵌套组
            Queue<Group> groupQueue = new LinkedList<>();
            groupQueue.addAll(fileGroup.subgroups);

            while (!groupQueue.isEmpty()) {
                Group current = groupQueue.poll();

                // 检查当前组是否为空
                if (current.all_nodes().isEmpty()) {
                    current.remove_from_parent();
                } else {
                    // 添加子组继续检查
                    groupQueue.addAll(current.subgroups);
                }
            }
        });

        return newFileGroups;
    }
    public static SubsetResult filterForSubset(SubsetParams subsetParams,
                                               List<Node> allNodes,
                                               List<Edge> edges,
                                               List<Group> fileGroups) {
        // 参数校验
        if (subsetParams == null) {
            throw new IllegalArgumentException("SubsetParams cannot be null");
        }

        // 执行节点过滤
        Set<Node> newNodes = filterNodesForSubset(subsetParams, allNodes, edges);

        // 执行边过滤
        List<Edge> newEdges = filterEdgesForSubset(newNodes, edges);

        // 执行组结构过滤（注意传递防御性拷贝）
        List<Group> newFileGroups = filterGroupsForSubset(newNodes, new ArrayList<>(fileGroups));

        // 转换节点集合为有序列表
        List<Node> orderedNodes = new ArrayList<>(newNodes);

        return new SubsetResult(newFileGroups, orderedNodes, newEdges);
    }
    public static class SubsetResult {
        public final List<Group> fileGroups;
        public final List<Node> nodes;
        public final List<Edge> edges;

        public SubsetResult(List<Group> fileGroups, List<Node> nodes, List<Edge> edges) {
            this.fileGroups = Collections.unmodifiableList(fileGroups);
            this.nodes = Collections.unmodifiableList(nodes);
            this.edges = Collections.unmodifiableList(edges);
        }
    }


    public static String generateJson(List<Node> nodes, List<Edge> edges) {
        // 构建节点字典
        Map<String, Object> nodeDicts = new LinkedHashMap<>();
        for (Node node : nodes) {
            nodeDicts.put(node.uid, node.to_dict());
        }

        // 构建边列表
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (Edge edge : edges) {
            edgeList.add(edge.to_dict());
        }

        // 组装完整数据结构
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("directed", true);
        graph.put("nodes", nodeDicts);
        graph.put("edges", edgeList);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("graph", graph);

        // 使用自定义序列化
        return JsonUtil.toJson(root);
    }

    private static class JsonUtil {
        public static String toJson(Object obj) {
            if (obj == null) {
                return "null";
            }
            if (obj instanceof String) {
                return "\"" + escape((String) obj) + "\"";
            }
            if (obj instanceof Boolean) {
                return obj.toString();
            }
            if (obj instanceof Number) {
                return obj.toString();
            }
            if (obj instanceof Map) {
                return mapToJson((Map<?, ?>) obj);
            }
            if (obj instanceof Iterable) {
                return iterableToJson((Iterable<?>) obj);
            }
            throw new IllegalArgumentException("Unsupported type: " + obj.getClass());
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }

        private static String mapToJson(Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                Object value = entry.getValue();
                if (!first) sb.append(",");
                sb.append("\"").append(escape(key)).append("\":").append(toJson(value));
                first = false;
            }
            return sb.append("}").toString();
        }

        private static String iterableToJson(Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            return sb.append("]").toString();
        }
    }
    private static final String LEGEND = "/* 这里是图例内容 */\n";
    public static void writeFile(Writer writer,
                                 List<Node> nodes,
                                 List<Edge> edges,
                                 List<Group> groups,
                                 boolean hideLegend,
                                 boolean noGrouping,
                                 boolean asJson) throws IOException {
        if (asJson) {
            String content = generateJson(nodes, edges);
            writer.write(content);
            return;
        }

        // 构建DOT文件内容
        StringBuilder content = new StringBuilder(1024);
        String splines = edges.size() >= 500 ? "polyline" : "ortho";

        // 添加图形基本设置
        content.append("digraph G {\n")
                .append("concentrate=true;\n")
                .append(String.format("splines=\"%s\";\n", splines))
                .append("rankdir=\"LR\";\n");

        // 添加图例
        if (!hideLegend) {
            content.append(LEGEND);
        }

        // 添加节点定义
        for (Node node : nodes) {
            content.append(node.to_dot()).append(";\n");
        }

        // 添加边定义
        for (Edge edge : edges) {
            content.append(edge.to_dot()).append(";\n");
        }

        // 添加分组信息
        if (!noGrouping) {
            for (Group group : groups) {
                content.append(group.to_dot());
            }
        }

        content.append("}\n");
        writer.write(content.toString());
    }
    private static final Set<String> LANGUAGES = Set.of("java", "py", "js", "cpp");
    public static String determineLanguage(List<String[]> individualFiles) {
        // 遍历所有文件信息
        for (String[] fileInfo : individualFiles) {
            String filename = fileInfo[0];

            // 提取文件后缀
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex == -1 || dotIndex == filename.length() - 1) {
                continue; // 跳过无后缀或以点结尾的文件
            }

            String suffix = filename.substring(dotIndex + 1).toLowerCase();

            // 检查后缀是否在支持列表中
            if (LANGUAGES.contains(suffix)) {
                System.out.println("[INFO] Implicitly detected language as '" + suffix + "'");
                return suffix;
            }
        }

        // 生成调试信息
        String fileList = individualFiles.stream()
                .map(f -> f[0])
                .collect(Collectors.joining(", "));

        throw new IllegalArgumentException("Language detection failed for files: ["
                + fileList + "]. Please specify language explicitly.");
    }
    private static class FileEntry {
        final String path;
        final boolean explicit;

        FileEntry(String path, boolean explicit) {
            this.path = path;
            this.explicit = explicit;
        }
    }
    public static Object[] getSourcesAndLanguage(List<String> rawSourcePaths, String language) {
        List<FileEntry> individualFiles = new ArrayList<>();

        // 收集所有文件（显式添加的或目录遍历的）
        for (String source : sorted(rawSourcePaths)) {
            File file = new File(source);
            if (file.isFile()) {
                individualFiles.add(new FileEntry(file.getAbsolutePath(), true));
            } else if (file.isDirectory()) {
                collectDirectoryFiles(file, individualFiles);
            }
        }

        // 验证文件存在性
        if (individualFiles.isEmpty()) {
            throw new IllegalArgumentException("No source files found in: " + rawSourcePaths);
        }
        System.out.println("Found " + individualFiles.size() + " potential files");

        // 自动检测语言
        if (language == null || language.isEmpty()) {
            List<String> fileNames = new ArrayList<>();
            for (FileEntry fe : individualFiles) {
                fileNames.add(fe.path);
            }

        }

        // 过滤有效文件
        Set<String> sources = new HashSet<>();
        String finalLang = language.toLowerCase();
        for (FileEntry fe : individualFiles) {
            if (fe.explicit || hasExtension(fe.path, finalLang)) {
                sources.add(fe.path);
            } else {
                System.out.println("Skipping " + fe.path + " (not a " + finalLang + " file)");
            }
        }

        // 验证最终结果
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No valid " + language + " files found");
        }

        List<String> sortedSources = sorted(sources);
        System.out.println("Processing " + sortedSources.size() + " files:");
        sortedSources.forEach(s -> System.out.println("  " + s));

        return new Object[]{sortedSources, language};
    }

    private static List<String> sorted(Iterable<String> items) {
        List<String> list = new ArrayList<>();
        for (String s : items) list.add(s);
        Collections.sort(list);
        return list;
    }

    private static void collectDirectoryFiles(File dir, List<FileEntry> collector) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                collectDirectoryFiles(f, collector);
            } else if (f.isFile()) {
                collector.add(new FileEntry(f.getAbsolutePath(), false));
            }
        }
    }
    public static engine.SubsetResult mapIt(
            List<String> sources,
            String extension,
            boolean noTrimming,
            List<String> excludeNamespaces,
            List<String> excludeFunctions,
            List<String> includeOnlyNamespaces,
            List<String> includeOnlyFunctions,
            boolean skipParseErrors,
            LanguageParams langParams) {

        // 获取语言处理器
        LanguageProcessor language = LanguageProcessorFactory.getProcessor(extension);
        language.assertDependencies();

        // 解析源文件AST
        List<Object[]> fileAstTrees = new ArrayList<>();
        for (String source : sources) {
            try {
                Object ast = language.getTree(source, langParams);
                fileAstTrees.add(new Object[]{source, ast});
            } catch (Exception ex) {
                if (skipParseErrors) {
                    System.out.println("Warning: Could not parse " + source + ". Skipping...");
                } else {
                    throw new RuntimeException(ex);
                }
            }
        }

        // 创建文件组
        List<Group> fileGroups = new ArrayList<>();
        for (Object[] fileAst : fileAstTrees) {
            String filename = (String) fileAst[0];
            Object tree = fileAst[1];
            Group fileGroup = GroupCreator.makeFileGroup(tree, filename, extension);
            fileGroups.add(fileGroup);
        }

        // 过滤命名空间
        if (!excludeNamespaces.isEmpty() || !includeOnlyNamespaces.isEmpty()) {
            fileGroups = filterNamespaces(fileGroups, excludeNamespaces, includeOnlyNamespaces);
        }

        // 过滤函数
        if (!excludeFunctions.isEmpty() || !includeOnlyFunctions.isEmpty()) {
            fileGroups = filterFunctions(fileGroups, excludeFunctions, includeOnlyFunctions);
        }

        // 收集所有节点和子组
        List<Group> allSubgroups = fileGroups.stream()
                .flatMap(g -> g.all_groups().stream())
                .collect(Collectors.toList());

        List<Node> allNodes = fileGroups.stream()
                .flatMap(g -> g.all_nodes().stream())
                .collect(Collectors.toList());

        // 构建继承关系
        Map<String, List<Node>> nodesBySubgroupToken = new HashMap<>();
        for (Group subgroup : allSubgroups) {
            String token = subgroup.getToken();
            nodesBySubgroupToken.computeIfAbsent(token, k -> new ArrayList<>())
                    .addAll(subgroup.all_nodes());
        }

        for (Group fileGroup : fileGroups) {
            for (Group subgroup : fileGroup.all_groups()) {
                List<List<Node>> inherits = subgroup.get_variables(1).stream()
                        .map(nodesBySubgroupToken::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                for (List<Node> inheritNodes : inherits) {
                    for (Node node : subgroup.all_nodes()) {
                        inheritNodes.forEach(n ->
                                node.variables.add(new Variable(n.getToken(), n, n.getLineNumber())));
                    }
                }
            }
        }

        // 解析变量
        List<Group> finalFileGroups = fileGroups;
        allNodes.forEach(node -> node.resolve_variables(finalFileGroups));

        // 构建调用关系
        List<Edge> edges = new ArrayList<>();
        List<Call> badCalls = new ArrayList<>();

        for (Node nodeA : allNodes) {
            List<CallLinker.FindLinkResult> links = engine.findLinks(nodeA, allNodes);
            for (CallLinker.FindLinkResult link : links) {
                if (link.unresolvedCall != null) {
                    badCalls.add(link.unresolvedCall);
                }
                if (link.matchedNode != null) {
                    edges.add(new Edge(nodeA, link.matchedNode));
                }
            }
        }

        // 处理未连接的节点
        Set<Node> nodesWithEdges = new HashSet<>();
        edges.forEach(edge -> {
            nodesWithEdges.add(edge.node0);
            nodesWithEdges.add(edge.node1);
        });

        if (!noTrimming) {
            // 移除未连接的节点
            allNodes.stream()
                    .filter(n -> !nodesWithEdges.contains(n))
                    .forEach(Node::remove_from_parent);

            // 清理空组
            fileGroups.removeIf(g -> g.all_nodes().isEmpty());
            allNodes = new ArrayList<>(nodesWithEdges);
        }

        return new engine.SubsetResult(
                fileGroups,
                new ArrayList<>(nodesWithEdges),
                edges
        );
    }

    // 辅助过滤方法
    private static List<Group> filterNamespaces(List<Group> groups,
                                                List<String> exclude, List<String> includeOnly) {
        return groups.stream()
                .filter(g -> {
                    String ns = g.getToken();
                    if (!includeOnly.isEmpty())
                        return includeOnly.contains(ns);
                    return !exclude.contains(ns);
                })
                .collect(Collectors.toList());
    }

    private static List<Group> filterFunctions(List<Group> groups,
                                               List<String> exclude, List<String> includeOnly) {
        return groups.stream()
                .map(g -> {
                    Group cloned = new Group(g);
                    cloned.subgroups.clear();
                    cloned.nodes = cloned.all_nodes().stream()
                            .filter(n -> {
                                String func = n.getToken();
                                if (!includeOnly.isEmpty())
                                    return includeOnly.contains(func);
                                return !exclude.contains(func);
                            })
                            .collect(Collectors.toList());
                    return cloned;
                })
                .filter(g -> !g.all_nodes().isEmpty())
                .collect(Collectors.toList());
    }

    private static boolean hasExtension(String path, String ext) {
        int dotIndex = path.lastIndexOf('.');
        return dotIndex != -1 && path.substring(dotIndex + 1).equalsIgnoreCase(ext);
    }

    private static void traverseDependencies(
            Set<Node> includedNodes,
            Node startNode,
            Map<Node, Set<Node>> dependencyMap,
            int depth,
            boolean isUpstream
    ) {
        Set<Node> currentLayer = new HashSet<>();
        currentLayer.add(startNode);
        int currentDepth = 0;

        while (!currentLayer.isEmpty() && (depth == -1 || currentDepth < depth)) {
            Set<Node> nextLayer = new HashSet<>();

            for (Node node : currentLayer) {
                Set<Node> dependencies = dependencyMap.getOrDefault(node, Collections.emptySet());
                for (Node dep : dependencies) {
                    if (!includedNodes.contains(dep)) {
                        includedNodes.add(dep);
                        nextLayer.add(dep);
                    }
                }
            }

            currentLayer = nextLayer;
            if (depth != -1) currentDepth++;
        }
    }
}
class LanguageParams {
    public final String sourceType;
    public final String rubyVersion;

    public LanguageParams() {
        this("script", "27");
    }

    public LanguageParams(String sourceType, String rubyVersion) {
        this.sourceType = sourceType;
        this.rubyVersion = rubyVersion;
    }
}
 class SubsetParams {
    public final String targetFunction;
    public final int upstreamDepth;
    public final int downstreamDepth;

    private SubsetParams(String targetFunction, int upstreamDepth, int downstreamDepth) {
        this.targetFunction = targetFunction;
        this.upstreamDepth = upstreamDepth;
        this.downstreamDepth = downstreamDepth;
    }


    public static SubsetParams generate(String targetFunction, int upstreamDepth, int downstreamDepth) {
        if ((upstreamDepth != -1 || downstreamDepth != -1) && targetFunction == null) {
            throw new IllegalArgumentException("Depth parameters require target function specification");
        }

        if (targetFunction == null) {
            return null;
        }

        if (upstreamDepth < -1 || downstreamDepth < -1) {
            throw new IllegalArgumentException("Depth values must be >= -1");
        }

        if (upstreamDepth == -1 && downstreamDepth == -1) {
            throw new IllegalArgumentException("At least one depth direction must be specified");
        }

        return new SubsetParams(targetFunction, upstreamDepth, downstreamDepth);
    }
}
class GroupCreator {

    public static Group makeFileGroup(Object tree, String filename, String extension) {
        // 根据文件扩展名获取对应的语言处理器
        LanguageProcessor language = LanguageProcessorFactory.getProcessor(extension);

        // 分离AST中的不同组成部分
        NamespaceSeparationResult separationResult = language.separateNamespaces(tree);
        List<Object> subgroupTrees = separationResult.getSubgroupTrees();
        List<Object> nodeTrees = separationResult.getNodeTrees();
        List<Object> bodyTrees = separationResult.getBodyTrees();

        // 处理文件名生成token
        File file = new File(filename);
        String baseName = file.getName();
        String token = baseName.replaceAll("\\." + extension + "$", "");

        // 创建文件组对象
        Group fileGroup = new Group(
                token,
                GroupType.FILE.toString(),
                "File",
                language.getFileImportTokens(filename),
                0,
                null,
                null
        );

        // 添加普通节点
        for (Object nodeTree : nodeTrees) {
            List<Node> newNodes = language.createNodes(nodeTree, fileGroup);
            for (Node node : newNodes) {
                fileGroup.add_node(node, false);
            }
        }

        // 添加根节点
        Node rootNode = language.createRootNode(bodyTrees, fileGroup);
        fileGroup.add_node(rootNode, true);

        // 添加子组
        for (Object subgroupTree : subgroupTrees) {
            Group classGroup = language.createClassGroup(subgroupTree, fileGroup);
            fileGroup.add_subgroup(classGroup);
        }

        return fileGroup;
    }
}

// 以下是需要的辅助类和接口定义

enum GroupType {
    FILE, CLASS, NAMESPACE;
}

interface LanguageProcessor {
    NamespaceSeparationResult separateNamespaces(Object tree);
    List<Node> createNodes(Object nodeTree, Group parent);
    Node createRootNode(List<Object> bodyTrees, Group parent);
    Group createClassGroup(Object subgroupTree, Group parent);
    List<String> getFileImportTokens(String filename);

    void assertDependencies();

    Object getTree(String source, LanguageParams langParams);
}

class NamespaceSeparationResult {
    private List<Object> subgroupTrees;
    private List<Object> nodeTrees;
    private List<Object> bodyTrees;

    public NamespaceSeparationResult(List<Object> subgroups, List<Object> nodes, List<Object> bodies) {
        this.subgroupTrees = subgroups;
        this.nodeTrees = nodes;
        this.bodyTrees = bodies;
    }

    public List<Object> getSubgroupTrees() { return subgroupTrees; }
    public List<Object> getNodeTrees() { return nodeTrees; }
    public List<Object> getBodyTrees() { return bodyTrees; }
}

class LanguageProcessorFactory {
    public static LanguageProcessor getProcessor(String extension) {
        // 实际实现应根据扩展名返回对应的处理器实例
        switch (extension.toLowerCase()) {
            case "java": return new JavaProcessor();
            // 添加其他语言处理器的case分支
            default: throw new IllegalArgumentException("Unsupported language");
        }
    }
}

// 示例Java语言处理器
class JavaProcessor implements LanguageProcessor {
    @Override
    public NamespaceSeparationResult separateNamespaces(Object tree) {
        // 实际实现应解析AST并分离不同部分
        return new NamespaceSeparationResult(
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );
    }

    @Override
    public List<Node> createNodes(Object nodeTree, Group parent) {
        return new ArrayList<>();
    }

    @Override
    public Node createRootNode(List<Object> bodyTrees, Group parent) {
        return new Node("root", new ArrayList<>(), new ArrayList<>(), parent, new ArrayList<>(), 0, false);
    }

    @Override
    public Group createClassGroup(Object subgroupTree, Group parent) {
        return new Group("class", GroupType.CLASS.toString(), "Class", new ArrayList<>(), 0, parent, null);
    }

    @Override
    public List<String> getFileImportTokens(String filename) {
        return new ArrayList<>();
    }

    @Override
    public void assertDependencies() {

    }

    @Override
    public Object getTree(String source, LanguageParams langParams) {
        return null;
    }
}
class CallLinker {

    public static class FindLinkResult {
        public final Node matchedNode;
        public final Call unresolvedCall;

        public FindLinkResult(Node matchedNode, Call unresolvedCall) {
            this.matchedNode = matchedNode;
            this.unresolvedCall = unresolvedCall;
        }
    }

    public static FindLinkResult findLinkForCall(Call call, Node nodeA, List<Node> allNodes) {
        // 获取调用点之前的所有变量
        List<Variable> allVars = nodeA.get_variables(call.line_number);

        // 第一轮检查：变量匹配
        for (Variable var : allVars) {
            Object varMatch = call.matches_variable(var);

            if (varMatch != null) {
                // 处理未知模块的情况
                if (Model.OWNER_CONST.get("UNKNOWN_MODULE").equals(varMatch)) {
                    return new FindLinkResult(null, null);
                }

                if (varMatch instanceof Node) {
                    return new FindLinkResult((Node) varMatch, null);
                }

                throw new AssertionError("Expected Node type but got: " + varMatch.getClass());
            }
        }

        List<Node> possibleNodes = new ArrayList<>();
        boolean isAttrCall = call.is_attr();

        // 第二轮检查：可能的节点匹配
        if (isAttrCall) {
            Group fileGroup = nodeA.file_group();

            for (Node node : allNodes) {
                boolean tokenMatches = call.token.equals(node.token);
                boolean notSameFileGroup = !node.parent.equals(fileGroup);

                if (tokenMatches && notSameFileGroup) {
                    possibleNodes.add(node);
                }
            }
        } else {
            for (Node node : allNodes) {
                Group parentGroup = (node.parent instanceof Group) ? (Group) node.parent : null;

                // 情况1：直接匹配函数名且属于文件级
                boolean isFileLevelFunction = parentGroup != null
                        && Model.GROUP_TYPE.get("FILE").equals(parentGroup.group_type)
                        && call.token.equals(node.token);

                // 情况2：匹配构造函数
                boolean isConstructorMatch = parentGroup != null
                        && call.token.equals(parentGroup.token)
                        && node.is_constructor;

                if (isFileLevelFunction || isConstructorMatch) {
                    possibleNodes.add(node);
                }
            }
        }

        // 处理匹配结果
        if (possibleNodes.size() == 1) {
            return new FindLinkResult(possibleNodes.get(0), null);
        } else if (possibleNodes.size() > 1) {
            return new FindLinkResult(null, call);
        }

        return new FindLinkResult(null, null);
    }
}