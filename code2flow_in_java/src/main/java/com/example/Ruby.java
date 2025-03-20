package com.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.Model.Group;
import com.example.Model.Node;
import com.example.Model.Call;
import com.example.Model.Variable;

public class Ruby {

    public static Object jsonToJava(JSONObject jsonObject) {
        return jsonObject.toMap();
    }

    public static List<Object> jsonToJava(JSONArray jsonArray) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            Object element = jsonArray.get(i);
            if (element instanceof JSONArray) {
                list.add(jsonToJava((JSONArray) element));
            } else if (element instanceof JSONObject) {
                list.add(jsonToJava((JSONObject) element));
            } else {
                list.add(element);
            }
        }
        return list;
    }

    public static String resolve_owner(Object owner_el) {
        if (owner_el == null || !(owner_el instanceof List<?>)) {
            return null;
        }
        List<?> ownerList = (List<?>) owner_el;
        if (ownerList.isEmpty()) {
            return null;
        }
        Object first = ownerList.get(0);
        if ("begin".equals(first)) {
            return Model.OWNER_CONST.get("UNKNOWN_VAR");
        }
        if ("send".equals(first)) {
            return Model.OWNER_CONST.get("UNKNOWN_VAR");
        }
        if ("lvar".equals(first)) {
            return ownerList.get(1).toString();
        }
        if ("ivar".equals(first)) {
            return ownerList.get(1).toString();
        }
        if ("self".equals(first)) {
            return "self";
        }
        if ("const".equals(first)) {
            return ownerList.get(2).toString();
        }
    
        return Model.OWNER_CONST.get("UNKNOWN_VAR");
    }

    public static Call get_call_from_send_el(List<Object> func_el) {
        Object owner_el = func_el.get(1);
        String token = func_el.get(2).toString();
        String owner = resolve_owner(owner_el);
        if (owner != null && "new".equals(token)) {
            return new Call(owner, null, null, false);
        }
        return new Call(token, null, owner, false);
    }

    public static List<List<Object>> walk(Object tree_el) {
        List<List<Object>> ret = new ArrayList<>();
        if (tree_el == null || !(tree_el instanceof List<?>)) {
            return ret;
        }
        List<Object> treeList = (List<Object>) tree_el;
        ret.add(treeList);
        for (Object el : treeList) {
            if (el instanceof List<?>) {
                ret.addAll(walk(el));
            }
        }
        return ret;
    }

    public static List<Call> make_calls(List<Object> body_el) {
        List<Call> calls = new ArrayList<>();
        for (List<Object> el : walk(body_el)) {
            if (!el.isEmpty() && "send".equals(el.get(0))) {
                calls.add(get_call_from_send_el(el));
            }
        }
        return calls;
    }

    public static Variable process_assign(List<Object> assignment_el) {
        assert "lvasgn".equals(assignment_el.get(0));
        String varname = assignment_el.get(1).toString();
        List<Object> secondEl = (List<Object>) assignment_el.get(2);
        if ("send".equals(secondEl.get(0))) {
            Call call = get_call_from_send_el(secondEl);
            return new Variable(varname, call, null);
        }
        return null;
    }

    public static List<Variable> make_local_variables(List<Object> tree_el, Group parent) {
        List<Variable> variables = new ArrayList<>();
        for (Object elobj : tree_el) {
            if (elobj instanceof List<?>) {
                List<Object> el = (List<Object>) elobj;
                if (!el.isEmpty() && "lvasgn".equals(el.get(0))) {
                    Variable var = process_assign(el);
                    if (var != null) {
                        variables.add(var);
                    }
                }
            }
        }
        if (parent != null && parent.group_type == Model.GROUP_TYPE.get("CLASS")) {
            variables.add(new Variable("self", parent, null));
        }
        return variables;
    }

    public static List<List<Object>> as_lines(List<Object> tree_el) {
        List<List<Object>> result = new ArrayList<>();
        if (tree_el == null || tree_el.isEmpty()) {
            return result;
        }
        Object first = tree_el.get(0);
        if (first instanceof List<?>) {
            for (Object el : tree_el) {
                result.add((List<Object>) el);
            }
            return result;
        }
        if ("begin".equals(first)) {
            result.add(tree_el);
            return result;
        }
        result.add(tree_el);
        return result;
    }

    public static List<List<Object>> get_tree_body(List<Object> tree_el) {
        Object body_struct;
        if ("module".equals(tree_el.get(0))) {
            body_struct = tree_el.get(2);
        } else if ("defs".equals(tree_el.get(0))) {
            body_struct = tree_el.get(4);
        } else {
            body_struct = tree_el.get(3);
        }
        return as_lines((List<Object>) body_struct);
    }

    public static List<String> get_inherits(List<Object> tree, List<List<Object>> body_tree) {
        List<String> inherits = new ArrayList<>();
        if ("class".equals(tree.get(0)) && tree.get(2) != null) {
            List<Object> ext = (List<Object>) tree.get(2);
            inherits.add(ext.get(2).toString());
        }
        if ("module".equals(tree.get(0))) {
            List<Object> mod = (List<Object>) tree.get(1);
            inherits.add(mod.get(2).toString());
        }
        for (List<Object> el : body_tree) {
            if (!el.isEmpty() && "send".equals(el.get(0)) && "include".equals(el.get(2))) {
                List<Object> incl = (List<Object>) el.get(3);
                inherits.add(incl.get(2).toString());
            }
        }
        return inherits;
    }

    public static void assert_dependencies() {
        if (!Model.is_installed("ruby-parse")) {
            throw new AssertionError("The 'parser' gem is requred to parse ruby files but was not found on the path. Install it from gem and try again.");
        }
    }

    public static List<Object> get_tree(String filename, LanguageParams lang_params) {
        String version_flag = "--" + lang_params.ruby_version;
        List<String> cmd = new ArrayList<>(Arrays.asList("ruby-parse", "--emit-json", version_flag, filename));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = null;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            int exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("Error executing ruby-parse command: " + e.getMessage());
        }
        List<Object> tree;
        try {
            JSONArray jsonArray = new JSONArray(output.toString());
            Object conv = jsonToJava(jsonArray);
            tree = (List<Object>) conv;
        } catch (JSONException e) {
            throw new AssertionError(
                "Ruby-parse could not parse file " + filename + ". You may have a syntax error. For more detail, try running the command `ruby-parse " + filename + "`."
            );
        }
        assert tree instanceof List<?> : "Parsed tree is not a list";
        Object first = tree.get(0);
        if (!(first.equals("module") || first.equals("begin"))) {
            List<Object> newTree = new ArrayList<>();
            newTree.add(tree);
            tree = newTree;
        }
        return tree;
    }

    public static Triple<List<List<Object>>, List<List<Object>>, List<List<Object>>> separate_namespaces(List<Object> tree) {
        List<List<Object>> groups = new ArrayList<>();
        List<List<Object>> nodes = new ArrayList<>();
        List<List<Object>> body = new ArrayList<>();
        for (List<Object> el : as_lines(tree)) {
            if (!el.isEmpty() && ( "def".equals(el.get(0)) || "defs".equals(el.get(0)) )) {
                nodes.add(el);
            } else if (!el.isEmpty() && ( "class".equals(el.get(0)) || "module".equals(el.get(0)) )) {
                groups.add(el);
            } else {
                body.add(el);
            }
        }
        return new Triple<>(groups, nodes, body);
    }

    public static List<Node> make_nodes(List<Object> tree, Group parent) {
        String token;
        if ("defs".equals(tree.get(0))) {
            token = tree.get(2).toString();
        } else {
            token = tree.get(1).toString();
        }
        boolean is_constructor = token.equals("initialize") && parent.group_type == Model.GROUP_TYPE.get("CLASS");
        List<List<Object>> tree_body = get_tree_body(tree);
        Triple<List<List<Object>>, List<List<Object>>, List<List<Object>>> separated = separate_namespaces(flattenLines(tree_body));
        List<List<Object>> subgroup_trees = separated.first;
        List<List<Object>> subnode_trees = separated.second;
        assert subgroup_trees.isEmpty() : "subgroup_trees should be empty";
        List<Call> calls = make_calls(flattenLines(separated.third));
        List<Variable> variables = make_local_variables(flattenLines(separated.third), parent);
        Node node = new Node(token, calls, variables, parent, null, null, is_constructor);
        
        List<List<Node>> subnodesList = new ArrayList<>();
        for (List<Object> t : subnode_trees) {
            subnodesList.add(make_nodes(t, parent));
        }
        List<Node> subnodes = Model.flatten(subnodesList);
        List<Node> result = new ArrayList<>();
        result.add(node);
        result.addAll(subnodes);
        return result;
    }

    public static Node make_root_node(List<Object> lines, Group parent) {
        String token = "(global)";
        List<Call> calls = make_calls(lines);
        List<Variable> variables = make_local_variables(lines, parent);
        Node root_node = new Node(token, calls, variables, parent, null, null, false);
        return root_node;
    }

    public static Group make_class_group(List<Object> tree, Group parent) {
        assert "class".equals(tree.get(0)) || "module".equals(tree.get(0));
        List<List<Object>> tree_body = get_tree_body(tree);
        Triple<List<List<Object>>, List<List<Object>>, List<List<Object>>> separated = separate_namespaces(flattenLines(tree_body));
        List<List<Object>> subgroup_trees = separated.first;
        List<List<Object>> node_trees = separated.second;
        List<List<Object>> body_trees = separated.third;
    
        String group_type = Model.GROUP_TYPE.get("CLASS");
        if ("module".equals(tree.get(0))) {
            group_type = Model.GROUP_TYPE.get("NAMESPACE");
        }
        String display_type = tree.get(0).toString().substring(0, 1).toUpperCase() + tree.get(0).toString().substring(1);
        List<Object> secondEl = (List<Object>) tree.get(1);
        String token = secondEl.get(2).toString();
        
        List<String> inherits = get_inherits(tree, body_trees);
        Group class_group = new Group(token, group_type, display_type, null, null, parent, inherits);
        
        for (List<Object> subgroup_tree : subgroup_trees) {
            class_group.add_subgroup(make_class_group(subgroup_tree, class_group));
        }
    
        for (List<Object> node_tree : node_trees) {
            for (Node new_node : make_nodes(node_tree, class_group)) {
                class_group.add_node(new_node, false);
            }
        }
        for (Node node : class_group.nodes) {
            for (Node n : class_group.nodes) {
                node.variables.add(new Variable(n.token, n, null));
            }
        }
    
        return class_group;
    }

    public static List<String> file_import_tokens(String filename) {
        return new ArrayList<>();
    }
    
    private static List<Object> flattenLines(List<List<Object>> lines) {
        List<Object> flat = new ArrayList<>();
        for (List<Object> line : lines) {
            flat.addAll(line);
        }
        return flat;
    }
}

class Triple<F, S, T> {
    public final F first;
    public final S second;
    public final T third;
    
    public Triple(F first, S second, T third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }
}

class LanguageParams {
    public String ruby_version;
    
    public LanguageParams(String ruby_version) {
        this.ruby_version = ruby_version;
    }
    
}