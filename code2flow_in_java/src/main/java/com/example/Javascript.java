package com.example;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.logging.Logger;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.nio.file.Path;
import org.json.JSONObject;
import org.json.JSONArray;

public class Javascript {
    private static final Logger logging = Logger.getLogger("Javascript");

    public static int lineno(Object el) {
        if (el instanceof List) {
            List<?> listEl = (List<?>) el;
            if (!listEl.isEmpty()) {
                el = listEl.get(0);
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> elMap = (Map<String, Object>) el;
        @SuppressWarnings("unchecked")
        Map<String, Object> loc = (Map<String, Object>) elMap.get("loc");
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) loc.get("start");
        Object retObj = start.get("line");
        if (!(retObj instanceof Integer)) {
            throw new AssertionError("Line number is not an integer");
        }
        int ret = (Integer) retObj;
        return ret;
    }

    public static List<Map<String, Object>> walk(Object tree) {
        List<Map<String, Object>> ret = new ArrayList<>();
        if (tree instanceof List) {
            List<?> treeList = (List<?>) tree;
            for (Object elObj : treeList) {
                if (elObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> el = (Map<String, Object>) elObj;
                    if (el.get("type") != null) {
                        ret.add(el);
                        ret.addAll(walk(el));
                    }
                }
            }
        } else if (tree instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> treeMap = (Map<String, Object>) tree;
            for (Object value : treeMap.values()) {
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vMap = (Map<String, Object>) value;
                    if (vMap.get("type") != null) {
                        ret.add(vMap);
                        ret.addAll(walk(vMap));
                    }
                }
                if (value instanceof List) {
                    ret.addAll(walk(value));
                }
            }
        }
        return ret;
    }

    public static String resolve_owner(Map<String, Object> callee) {
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) callee.get("object");
        String objType = (String) obj.get("type");
        if ("ThisExpression".equals(objType)) {
            return "this";
        }
        if ("Identifier".equals(objType)) {
            return (String) obj.get("name");
        }
        if ("MemberExpression".equals(objType)) {
            if (obj.containsKey("object") && ((Map<String, Object>) obj.get("property")).containsKey("name")) {
                return Model.djoin(resolve_owner(obj), (String) ((Map<String, Object>) obj.get("property")).get("name"));
            }
            return Model.OWNER_CONST.get("UNKNOWN_VAR");
        }
        if ("CallExpression".equals(objType)) {
            return Model.OWNER_CONST.get("UNKNOWN_VAR");
        }
        if ("NewExpression".equals(objType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> calleeObj = (Map<String, Object>) obj.get("callee");
            if (calleeObj.containsKey("name")) {
                return (String) calleeObj.get("name");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> calleeObjObj = (Map<String, Object>) calleeObj.get("object");
            @SuppressWarnings("unchecked")
            Map<String, Object> calleeObjProp = (Map<String, Object>) calleeObj.get("property");
            return Model.djoin((String) calleeObjObj.get("name"), (String) calleeObjProp.get("name"));
        }
        return Model.OWNER_CONST.get("UNKNOWN_VAR");
    }

    public static Call get_call_from_func_element(Map<String, Object> func) {
        @SuppressWarnings("unchecked")
        Map<String, Object> callee = (Map<String, Object>) func.get("callee");
        String type = (String) callee.get("type");
        if ("MemberExpression".equals(type) && ((Map<String, Object>) callee.get("property")).containsKey("name")) {
            String owner_token = resolve_owner(callee);
            return new Call((String) ((Map<String, Object>) callee.get("property")).get("name"), lineno(callee), owner_token, false);
        }
        if ("Identifier".equals(type)) {
            return new Call((String) callee.get("name"), lineno(callee), null, false);
        }
        return null;
    }

    public static List<Call> make_calls(Object body) {
        List<Call> calls = new ArrayList<>();
        for (Map<String, Object> element : walk(body)) {
            String type = (String) element.get("type");
            if ("CallExpression".equals(type)) {
                Call call = get_call_from_func_element(element);
                if (call != null) {
                    calls.add(call);
                }
            } else if ("NewExpression".equals(type)
                    && ((Map<String, Object>) element.get("callee")).get("type").equals("Identifier")) {
                calls.add(new Call((String) ((Map<String, Object>) element.get("callee")).get("name"), lineno(element), null, false));
            }
        }
        return calls;
    }

    public static List<Variable> process_assign(Map<String, Object> element) {
        @SuppressWarnings("unchecked")
        List<Object> declarations = (List<Object>) element.get("declarations");
        if (declarations.size() > 1) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) declarations.get(0);
        if (!"VariableDeclarator".equals(target.get("type"))) {
            throw new AssertionError("Expected VariableDeclarator");
        }
        if (target.get("init") == null) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> init = (Map<String, Object>) target.get("init");
        if ("NewExpression".equals(init.get("type"))) {
            String token = (String) ((Map<String, Object>) target.get("id")).get("name");
            Call call = get_call_from_func_element(init);
            if (call != null) {
                List<Variable> vars = new ArrayList<>();
                vars.add(new Variable(token, call, lineno(element)));
                return vars;
            }
        }
        if ("CallExpression".equals(init.get("type"))
                && ((Map<String, Object>) init.get("callee")).get("name") != null
                && ((Map<String, Object>) init.get("callee")).get("name").equals("require")) {
            String import_src_str = (String) ((Map<String, Object>) ((List<Object>) init.get("arguments")).get(0)).get("value");
            @SuppressWarnings("unchecked")
            Map<String, Object> idMap = (Map<String, Object>) target.get("id");
            if (idMap.containsKey("name")) {
                String imported_name = (String) idMap.get("name");
                String points_to_str = Model.djoin(import_src_str, imported_name);
                List<Variable> vars = new ArrayList<>();
                vars.add(new Variable(imported_name, points_to_str, lineno(element)));
                return vars;
            }
            List<Variable> ret = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Object> properties = (List<Object>) idMap.getOrDefault("properties", new ArrayList<>());
            for (Object propObj : properties) {
                @SuppressWarnings("unchecked")
                Map<String, Object> prop = (Map<String, Object>) propObj;
                String imported_name = (String) ((Map<String, Object>) prop.get("key")).get("name");
                String points_to_str = Model.djoin(import_src_str, imported_name);
                ret.add(new Variable(imported_name, points_to_str, lineno(element)));
            }
            return ret;
        }
        if ("ImportExpression".equals(init.get("type"))) {
            String import_src_str = (String) ((Map<String, Object>) init.get("source")).get("raw");
            String imported_name = (String) ((Map<String, Object>) target.get("id")).get("name");
            String points_to_str = Model.djoin(import_src_str, imported_name);
            List<Variable> vars = new ArrayList<>();
            vars.add(new Variable(imported_name, points_to_str, lineno(element)));
            return vars;
        }
        if ("CallExpression".equals(init.get("type"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> idMap = (Map<String, Object>) target.get("id");
            if (!idMap.containsKey("name")) {
                return new ArrayList<>();
            }
            Call call = get_call_from_func_element(init);
            if (call != null) {
                List<Variable> vars = new ArrayList<>();
                vars.add(new Variable((String) idMap.get("name"), call, lineno(element)));
                return vars;
            }
        }
        if ("ThisExpression".equals(init.get("type"))) {
            if (!(new HashSet<>(((Map<String, Object>) init).keySet())
                    .equals(new HashSet<>(Arrays.asList("start", "end", "loc", "type"))))) {
                throw new AssertionError("ThisExpression keys do not match");
            }
            return new ArrayList<>();
        }
        return new ArrayList<>();
    }

    public static List<Variable> make_local_variables(Object tree, Group parent) {
        if (tree == null) {
            return new ArrayList<>();
        }
        List<Variable> variables = new ArrayList<>();
        for (Map<String, Object> element : walk(tree)) {
            if ("VariableDeclaration".equals(element.get("type"))) {
                variables.addAll(process_assign(element));
            }
        }
        if (parent instanceof Group && Model.GROUP_TYPE.get("CLASS").equals(parent.group_type)) {
            variables.add(new Variable("this", parent, lineno(tree)));
        }
        List<Variable> filtered = new ArrayList<>();
        for (Variable var : variables) {
            if (var != null) {
                filtered.add(var);
            }
        }
        return filtered;
    }

    public static List<Map<String, Object>> children(Map<String, Object> tree) {
        if (!(tree instanceof Map)) {
            throw new AssertionError("tree is not a dict");
        }
        List<Map<String, Object>> ret = new ArrayList<>();
        for (Object v : tree.values()) {
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vMap = (Map<String, Object>) v;
                if (vMap.get("type") != null) {
                    ret.add(vMap);
                }
            }
            if (v instanceof List) {
                for (Object item : (List<?>) v) {
                    if (item instanceof Map) {
                        ret.add((Map<String, Object>) item);
                    }
                }
            }
        }
        return ret;
    }

    public static List<String> get_inherits(Map<String, Object> tree) {
        List<String> inherits = new ArrayList<>();
        if (tree.get("superClass") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> superClass = (Map<String, Object>) tree.get("superClass");
            if (superClass.containsKey("name")) {
                inherits.add((String) superClass.get("name"));
            } else {
                Map<String, Object> obj = (Map<String, Object>) superClass.get("object");
                Map<String, Object> prop = (Map<String, Object>) superClass.get("property");
                inherits.add(Model.djoin((String) obj.get("name"), (String) prop.get("name")));
            }
        }
        return inherits;
    }

    public static String get_acorn_version() {
        try {
            File currentFile = new File(Javascript.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File currentDir = currentFile.getParentFile();
            ProcessBuilder pb = new ProcessBuilder("node", "-p", "require('acorn/package.json').version");
            pb.directory(currentDir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                InputStream errorStream = proc.getErrorStream();
                String err = new String(errorStream.readAllBytes());
                throw new AssertionError("Acorn is required to parse javascript files. " +
                        "It was found on the path but could not be imported in node.\n" + err);
            }
            InputStream inputStream = proc.getInputStream();
            String output = new String(inputStream.readAllBytes());
            return output.trim();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void assert_dependencies() {
        if (!Model.is_installed("acorn")) {
            throw new AssertionError("Acorn is required to parse javascript files but was not found on the path. Install it from npm and try again.");
        }
        String version = get_acorn_version();
        if (!version.startsWith("8.")) {
            logging.warning(String.format("Acorn is required to parse javascript files. Version %s was found but code2flow has only been tested on 8.*", version));
        }
        logging.info("Using Acorn " + version);
    }

    public static Map<String, Object> get_tree(String filename, Map<String, String> lang_params) {
        try {
            File currentFile = new File(Javascript.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File currentDir = currentFile.getParentFile();
            Path scriptPath = Paths.get(currentDir.getAbsolutePath(), "get_ast.js");
            String script_loc = scriptPath.toString();
            List<String> cmd = new ArrayList<>();
            cmd.add("node");
            cmd.add(script_loc);
            cmd.add(lang_params.get("source_type"));
            cmd.add(filename);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            InputStream is = proc.getInputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new AssertionError(String.format("Acorn could not parse file %r. You may have a JS syntax error or if this is an es6-style source, you may need to run code2flow with --source-type=module. For more detail, try running the command \n  acorn %s\nWarning: Acorn CANNOT parse all javascript files. See their docs. ", filename, filename));
            }
            String output = outputStream.toString();
            JSONObject tree = new JSONObject(output);
            if (!"Program".equals(tree.getString("type"))) {
                throw new AssertionError("AST type is not Program");
            }
            return jsonToMap(tree);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> jsonToMap(JSONObject json) {
        Map<String, Object> ret = new HashMap<>();
        for (String key : json.keySet()) {
            Object value = json.get(key);
            if (value instanceof JSONObject) {
                value = jsonToMap((JSONObject) value);
            } else if (value instanceof JSONArray) {
                value = jsonToList((JSONArray) value);
            }
            ret.put(key, value);
        }
        return ret;
    }

    private static List<Object> jsonToList(JSONArray array) {
        List<Object> ret = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONObject) {
                value = jsonToMap((JSONObject) value);
            } else if (value instanceof JSONArray) {
                value = jsonToList((JSONArray) value);
            }
            ret.add(value);
        }
        return ret;
    }

    public static Object[] separate_namespaces(Map<String, Object> tree) {
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> body = new ArrayList<>();
        for (Map<String, Object> el : children(tree)) {
            String type = (String) el.get("type");
            if ("MethodDefinition".equals(type) || "FunctionDeclaration".equals(type)) {
                nodes.add(el);
            } else if ("ClassDeclaration".equals(type)) {
                groups.add(el);
            } else {
                Object[] tup = separate_namespaces(el);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tup0 = (List<Map<String, Object>>) tup[0];
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tup1 = (List<Map<String, Object>>) tup[1];
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tup2 = (List<Map<String, Object>>) tup[2];
                if (!tup0.isEmpty() || !tup1.isEmpty()) {
                    groups.addAll(tup0);
                    nodes.addAll(tup1);
                    body.addAll(tup2);
                } else {
                    body.add(el);
                }
            }
        }
        return new Object[]{groups, nodes, body};
    }

    public static List<Node> make_nodes(Map<String, Object> tree, Group parent) {
        boolean is_constructor = false;
        String token = null;
        String treeType = (String) tree.get("type");
        if (tree.get("kind") != null && "constructor".equals(tree.get("kind"))) {
            token = "(constructor)";
            is_constructor = true;
        } else if ("FunctionDeclaration".equals(treeType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> idMap = (Map<String, Object>) tree.get("id");
            token = (String) idMap.get("name");
        } else if ("MethodDefinition".equals(treeType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> keyMap = (Map<String, Object>) tree.get("key");
            token = (String) keyMap.get("name");
        }

        Object full_node_body;
        if ("FunctionDeclaration".equals(treeType)) {
            full_node_body = tree.get("body");
        } else {
            full_node_body = tree.get("value");
        }

        Object[] sep = separate_namespaces((Map<String, Object>) full_node_body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgroup_trees = (List<Map<String, Object>>) sep[0];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subnode_trees = (List<Map<String, Object>>) sep[1];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> this_scope_body = (List<Map<String, Object>>) sep[2];

        if (!subgroup_trees.isEmpty()) {
            logging.warning("Skipping class defined within a function!");
        }

        int line_number = lineno(tree);
        List<Call> calls = make_calls(this_scope_body);
        List<Variable> variables = make_local_variables(this_scope_body, parent);
        Node node = new Node(token, calls, variables, parent, null, line_number, is_constructor);

        List<List<Node>> subnodesLists = new ArrayList<>();
        for (Map<String, Object> t : subnode_trees) {
            subnodesLists.add(make_nodes(t, nodeToGroup(node)));
        }
        List<Node> subnodes = Model.flatten(subnodesLists);

        List<Node> result = new ArrayList<>();
        result.add(node);
        result.addAll(subnodes);
        return result;
    }

    private static Group nodeToGroup(Node node) {
        return new Group(node.token, "", "", null, node.line_number, null, null);
    }

    public static Node make_root_node(List<Map<String, Object>> lines, Group parent) {
        String token = "(global)";
        List<Call> calls = make_calls(lines);
        List<Variable> variables = make_local_variables(lines, parent);
        Node root_node = new Node(token, calls, variables, parent, null, 0, false);
        return root_node;
    }

    public static Group make_class_group(Map<String, Object> tree, Group parent) {
        if (!"ClassDeclaration".equals(tree.get("type"))) {
            throw new AssertionError("Expected ClassDeclaration");
        }
        Object[] sep = separate_namespaces(tree);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subgroup_trees = (List<Map<String, Object>>) sep[0];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> node_trees = (List<Map<String, Object>>) sep[1];
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body_trees = (List<Map<String, Object>>) sep[2];
        if (!subgroup_trees.isEmpty()) {
            throw new AssertionError("subgroup_trees should be empty");
        }

        String group_type = Model.GROUP_TYPE.get("CLASS");
        @SuppressWarnings("unchecked")
        String token = (String) ((Map<String, Object>) tree.get("id")).get("name");
        String display_name = "Class";
        int line_number = lineno(tree);
        List<String> inherits = get_inherits(tree);
        Group class_group = new Group(token, group_type, display_name, inherits, line_number, parent, null);

        for (Map<String, Object> node_tree : node_trees) {
            List<Node> new_nodes = make_nodes(node_tree, class_group);
            for (Node n : new_nodes) {
                class_group.add_node(n, false);
            }
        }
        return class_group;
    }

    public static List<String> file_import_tokens(String filename) {
        return new ArrayList<>();
    }
    
}