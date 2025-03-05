import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import model.Group;
import model.Node;
import model.Call;
import model.Variable;
import model.BaseLanguage;
import model.OWNER_CONST;
import model.GROUP_TYPE;
import model.is_installed;
import model.flatten;
import model.djoin;

class ASTUtils {

    public static int lineno(JSONObject tree) {
        return tree.getJSONObject("attributes").getInt("startLine");
    }

    public static String djoin(String... parts) {
        return String.join("\\", parts);
    }

    public static String djoin(JSONArray parts) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < parts.length(); i++) {
            list.add(parts.get(i).toString());
        }
        return String.join("\\", list);
    }

    public static <T> List<T> flatten(List<List<T>> lists) {
        List<T> ret = new ArrayList<>();
        for (List<T> list : lists) {
            ret.addAll(list);
        }
        return ret;
    }

    public static boolean is_installed(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "-v");
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static String get_name(JSONObject tree) {
        return get_name(tree, "name");
    }

    public static String get_name(JSONObject tree, String from) {
        if (tree.has("name") && tree.get("name") instanceof String) {
            return tree.getString("name");
        }
        if (tree.has("parts")) {
            return djoin(tree.getJSONArray("parts"));
        }
        if (tree.has(from)) {
            Object obj = tree.get(from);
            if (obj instanceof JSONObject) {
                return get_name((JSONObject)obj);
            }
        }
        return null;
    }

    public static Call get_call_from_expr(JSONObject func_expr) {
        String nodeType = func_expr.getString("nodeType");
        String token;
        String owner_token = null;
        if ("Expr_FuncCall".equals(nodeType)) {
            token = get_name(func_expr);
            owner_token = null;
        } else if ("Expr_New".equals(nodeType) && func_expr.getJSONObject("class").has("parts")) {
            token = "__construct";
            owner_token = get_name(func_expr.getJSONObject("class"));
        } else if ("Expr_MethodCall".equals(nodeType)) {
            token = get_name(func_expr);
            JSONObject varObj = func_expr.getJSONObject("var");
            if (varObj.has("var")) {
                owner_token = OWNER_CONST.UNKNOWN_VAR;
            } else {
                owner_token = get_name(varObj);
            }
        } else if ("Expr_BinaryOp_Concat".equals(nodeType) && func_expr.getJSONObject("right").getString("nodeType").equals("Expr_FuncCall")) {
            token = get_name(func_expr.getJSONObject("right"));
            JSONObject leftObj = func_expr.getJSONObject("left");
            if (leftObj.has("class")) {
                owner_token = get_name(leftObj.getJSONObject("class"));
            } else {
                owner_token = get_name(leftObj);
            }
        } else if ("Expr_StaticCall".equals(nodeType)) {
            token = get_name(func_expr);
            owner_token = get_name(func_expr.getJSONObject("class"));
        } else {
            return null;
        }
        if (owner_token != null && "__construct".equals(token)) {
            return new Call(owner_token, lineno(func_expr));
        }
        Call ret = new Call(token, owner_token, lineno(func_expr));
        return ret;
    }

    public static List<JSONObject> walk(Object tree) {
        List<JSONObject> ret = new ArrayList<>();
        if (tree instanceof JSONArray) {
            JSONArray arr = (JSONArray) tree;
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.get(i);
                if (el instanceof JSONObject) {
                    JSONObject jsonEl = (JSONObject) el;
                    if (jsonEl.has("nodeType")) {
                        ret.addAll(walk(jsonEl));
                    }
                }
            }
            return ret;
        }
        if (tree instanceof JSONObject) {
            JSONObject jsonTree = (JSONObject) tree;
            assert jsonTree.has("nodeType") : "JSONObject does not have nodeType";
            ret.add(jsonTree);
            if ("Expr_BinaryOp_Concat".equals(jsonTree.getString("nodeType"))) {
                return ret;
            }
            Iterator<String> keys = jsonTree.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object v = jsonTree.get(key);
                if (v instanceof JSONArray || (v instanceof JSONObject && ((JSONObject)v).has("nodeType"))) {
                    ret.addAll(walk(v));
                }
            }
            return ret;
        }
        return ret;
    }

    public static List<JSONObject> children(JSONObject tree) {
        List<JSONObject> ret = new ArrayList<>();
        Iterator<String> keys = tree.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = tree.get(key);
            if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object el = arr.get(i);
                    if (el instanceof JSONObject) {
                        JSONObject jsonEl = (JSONObject) el;
                        if (jsonEl.has("nodeType")) {
                            ret.add(jsonEl);
                        }
                    }
                }
            } else if (v instanceof JSONObject) {
                JSONObject jsonObj = (JSONObject) v;
                if (jsonObj.has("nodeType")) {
                    ret.add(jsonObj);
                }
            }
        }
        return ret;
    }

    public static List<Call> make_calls(Object body_el) {
        List<Call> calls = new ArrayList<>();
        List<JSONObject> exprs = walk(body_el);
        for (JSONObject expr : exprs) {
            Call call = get_call_from_expr(expr);
            calls.add(call);
        }
        List<Call> ret = new ArrayList<>();
        for (Call c : calls) {
            if (c != null) {
                ret.add(c);
            }
        }
        return ret;
    }

    public static Variable process_assign(JSONObject assignment_el) {
        assert "Expr_Assign".equals(assignment_el.getString("nodeType"))
               : "Node is not an assignment";
        JSONObject varObj = assignment_el.getJSONObject("var");
        if (!varObj.has("name")) {
            return null;
        }
        String varname = varObj.getString("name");
        Call call = get_call_from_expr(assignment_el.getJSONObject("expr"));
        if (call != null) {
            return new Variable(varname, call, lineno(assignment_el));
        }
        return null;
    }

    public static List<Variable> make_local_variables(Object tree_el, Group parent) {
        List<Variable> variables = new ArrayList<>();
        List<JSONObject> els = walk(tree_el);
        for (JSONObject el : els) {
            if ("Expr_Assign".equals(el.getString("nodeType"))) {
                variables.add(process_assign(el));
            }
            if ("Stmt_Use".equals(el.getString("nodeType"))) {
                JSONArray uses = el.getJSONArray("uses");
                for (int i = 0; i < uses.length(); i++) {
                    JSONObject use = uses.getJSONObject(i);
                    String owner_token = djoin(use.getJSONObject("name").getJSONArray("parts"));
                    String token = (use.isNull("alias") || use.get("alias") == JSONObject.NULL) ? owner_token : use.getJSONObject("alias").getString("name");
                    variables.add(new Variable(token, owner_token, lineno(el)));
                }
            }
        }
        if (parent != null && Objects.equals(parent.group_type, GROUP_TYPE.CLASS)) {
            variables.add(new Variable("this", parent, parent.line_number));
            variables.add(new Variable("self", parent, parent.line_number));
        }
        List<Variable> ret = new ArrayList<>();
        for (Variable v : variables) {
            if (v != null) {
                ret.add(v);
            }
        }
        return ret;
    }

    public static List<String> get_inherits(JSONObject tree) {
        List<String> ret = new ArrayList<>();
        if (tree.has("extends") && !(tree.get("extends") instanceof JSONObject && ((JSONObject)tree.get("extends")).isEmpty())) {
            ret.add(djoin(tree.getJSONObject("extends").getJSONArray("parts")));
        }
        if (tree.has("stmts")) {
            JSONArray stmts = tree.getJSONArray("stmts");
            for (int i = 0; i < stmts.length(); i++) {
                JSONObject stmt = stmts.getJSONObject(i);
                if ("Stmt_TraitUse".equals(stmt.getString("nodeType"))) {
                    JSONArray traits = stmt.getJSONArray("traits");
                    for (int j = 0; j < traits.length(); j++) {
                        JSONObject trait = traits.getJSONObject(j);
                        ret.add(djoin(trait.getJSONArray("parts")));
                    }
                }
            }
        }
        return ret;
    }

    public static ProcessResult run_ast_parser(String filename) {
        String script_loc = new File(new File(PHP.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent(), "get_ast.php").getAbsolutePath();
        List<String> cmd = Arrays.asList("php", script_loc, filename);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProcessResult result = new ProcessResult();
        try {
            Process proc = pb.start();
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            StringBuilder outputBuilder = new StringBuilder();
            String s;
            while ((s = stdInput.readLine()) != null) {
                outputBuilder.append(s).append("\n");
            }
            while ((s = stdError.readLine()) != null) {
                // Append errors if needed
            }
            proc.waitFor();
            result.output = outputBuilder.toString();
            result.returnCode = proc.exitValue();
        } catch (IOException | InterruptedException e) {
            result.output = "";
            result.returnCode = -1;
        }
        return result;
    }
}

class ProcessResult {
    public String output;
    public int returnCode;
}

class NamespaceSeparationResult {
    public List<JSONObject> groups;
    public List<JSONObject> nodes;
    public List<JSONObject> body;
    
    public NamespaceSeparationResult(List<JSONObject> groups, List<JSONObject> nodes, List<JSONObject> body) {
        this.groups = groups;
        this.nodes = nodes;
        this.body = body;
    }
}

public class PHP extends BaseLanguage {

    public static String assert_dependencies() {
        assert ASTUtils.is_installed("php") : "No php installation could be found";
        String self_ref = new File(new File(PHP.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent(), "get_ast.php").getAbsolutePath();
        ProcessResult pr = ASTUtils.run_ast_parser(self_ref);
        String path = new File(PHP.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
        String assert_msg = String.format("Error running the PHP parser. From the `%s` directory, run " +
                                          "`composer require nikic/php-parser \"^4.10\"`.", path);
        assert pr.returnCode == 0 : assert_msg;
        return pr.output;
    }

    public static JSONArray get_tree(String filename, Object lang_params) {
        ProcessResult pr = ASTUtils.run_ast_parser(filename);
        if (pr.returnCode != 0) {
            throw new AssertionError(String.format("Could not parse file %s. You may have a syntax error. For more detail, try running with `php %s`. ", filename, filename));
        }
        JSONArray tree = new JSONArray(pr.output);
        assert tree instanceof JSONArray : "Parsed tree is not a JSONArray";
        if (tree.length() == 1 && tree.getJSONObject(0).getString("nodeType").equals("Stmt_InlineHTML")) {
            throw new AssertionError("Tried to parse a file that is not likely PHP");
        }
        return tree;
    }

    public static NamespaceSeparationResult separate_namespaces(Object tree) {
        JSONArray treeArr;
        if (tree == null) {
            treeArr = new JSONArray();
        } else if (tree instanceof JSONArray) {
            treeArr = (JSONArray) tree;
        } else {
            treeArr = new JSONArray();
            treeArr.put(tree);
        }
        List<JSONObject> groups = new ArrayList<>();
        List<JSONObject> nodes = new ArrayList<>();
        List<JSONObject> body = new ArrayList<>();
        for (int i = 0; i < treeArr.length(); i++) {
            JSONObject el = treeArr.getJSONObject(i);
            String nodeType = el.getString("nodeType");
            if (nodeType.equals("Stmt_Function") || nodeType.equals("Stmt_ClassMethod") || nodeType.equals("Expr_Closure")) {
                nodes.add(el);
            } else if (nodeType.equals("Stmt_Class") || nodeType.equals("Stmt_Namespace") || nodeType.equals("Stmt_Trait")) {
                groups.add(el);
            } else {
                NamespaceSeparationResult tup = separate_namespaces(ASTUtils.children(el));
                if (!tup.groups.isEmpty() || !tup.nodes.isEmpty()) {
                    groups.addAll(tup.groups);
                    nodes.addAll(tup.nodes);
                    body.addAll(tup.body);
                } else {
                    body.add(el);
                }
            }
        }
        return new NamespaceSeparationResult(groups, nodes, body);
    }

    public static List<Node> make_nodes(JSONObject tree, Group parent) {
        String nodeType = tree.getString("nodeType");
        assert nodeType.equals("Stmt_Function") || nodeType.equals("Stmt_ClassMethod") || nodeType.equals("Expr_Closure")
               : "Node type is not a function, method, or closure";
        String token;
        if (nodeType.equals("Expr_Closure")) {
            token = "(Closure)";
        } else {
            token = tree.getJSONObject("name").getString("name");
        }
        boolean is_constructor = token.equals("__construct") && Objects.equals(parent.group_type, GROUP_TYPE.CLASS);
        JSONArray tree_body = tree.getJSONArray("stmts");
        NamespaceSeparationResult sep = separate_namespaces(tree_body);
        assert sep.groups.isEmpty() : "subgroup_trees should be empty";
        List<Call> calls = ASTUtils.make_calls(sep.body);
        List<Variable> variables = ASTUtils.make_local_variables(sep.body, parent);
        List<String> import_tokens;
        if (Objects.equals(parent.group_type, GROUP_TYPE.CLASS) && parent.parent != null && Objects.equals(parent.parent.group_type, GROUP_TYPE.NAMESPACE)) {
            import_tokens = Arrays.asList(ASTUtils.djoin(parent.parent.token, parent.token, token));
        } else if (Objects.equals(parent.group_type, GROUP_TYPE.NAMESPACE) || Objects.equals(parent.group_type, GROUP_TYPE.CLASS)) {
            import_tokens = Arrays.asList(ASTUtils.djoin(parent.token, token));
        } else {
            import_tokens = Arrays.asList(token);
        }
        Node node = new Node(token, calls, variables, parent, import_tokens, is_constructor, ASTUtils.lineno(tree));
        List<Node> subnodes = new ArrayList<>();
        for (JSONObject t : sep.nodes) {
            subnodes.addAll(make_nodes(t, parent));
        }
        List<Node> ret = new ArrayList<>();
        ret.add(node);
        ret.addAll(subnodes);
        return ret;
    }

    public static Node make_root_node(JSONArray lines, Group parent) {
        String token = "(global)";
        int line_number = lines.length() > 0 ? ASTUtils.lineno(lines.getJSONObject(0)) : 0;
        List<Call> calls = ASTUtils.make_calls(lines);
        List<Variable> variables = ASTUtils.make_local_variables(lines, parent);
        Node root_node = new Node(token, calls, variables, parent, new ArrayList<>(), false, line_number);
        return root_node;
    }

    public static Group make_class_group(JSONObject tree, Group parent) {
        String nodeType = tree.getString("nodeType");
        assert nodeType.equals("Stmt_Class") || nodeType.equals("Stmt_Namespace") || nodeType.equals("Stmt_Trait")
               : "Tree nodeType is not a class, namespace, or trait";
        NamespaceSeparationResult sep = separate_namespaces(tree.getJSONArray("stmts"));
        String token = ASTUtils.get_name(tree.getJSONObject("name"));
        String display_type = tree.getString("nodeType").substring(5);
        List<String> inherits = ASTUtils.get_inherits(tree);
        String group_type = GROUP_TYPE.CLASS;
        if (display_type.equals("Namespace")) {
            group_type = GROUP_TYPE.NAMESPACE;
        }
        List<String> import_tokens;
        if (display_type.equals("Class") && parent != null && Objects.equals(parent.group_type, GROUP_TYPE.NAMESPACE)) {
            import_tokens = Arrays.asList(ASTUtils.djoin(parent.token, token));
        } else {
            import_tokens = Arrays.asList(token);
        }
        Group class_group = new Group(token, group_type, display_type, import_tokens, parent, inherits, ASTUtils.lineno(tree));
        for (int i = 0; i < sep.groups.size(); i++) {
            class_group.add_subgroup(make_class_group(sep.groups.get(i), class_group));
        }
        for (int i = 0; i < sep.nodes.size(); i++) {
            List<Node> newNodes = make_nodes(sep.nodes.get(i), class_group);
            for (Node newNode : newNodes) {
                class_group.add_node(newNode);
            }
        }
        if (Objects.equals(group_type, GROUP_TYPE.NAMESPACE)) {
            class_group.add_node(make_root_node(new JSONArray(sep.body), class_group));
            for (Node node : class_group.nodes) {
                for (Node n : class_group.nodes) {
                    node.variables.add(new Variable(n.token, n, n.line_number));
                }
            }
        }
        return class_group;
    }

    public static List<String> file_import_tokens(String filename) {
        return new ArrayList<>();
    }
}