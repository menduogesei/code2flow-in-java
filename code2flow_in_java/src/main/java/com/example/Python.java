package com.example;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import com.example.Model.Group;
import com.example.Model.Node;
import com.example.Model.Call;
import com.example.Model.Variable;

class NamespaceSeparation {
    public List<Object> groups;
    public List<Object> nodes;
    public List<Object> body;
    public NamespaceSeparation(List<Object> groups, List<Object> nodes, List<Object> body) {
        this.groups = groups;
        this.nodes = nodes;
        this.body = body;
    }
}

public class Python {
    private static final Logger logger = Logger.getLogger(Python.class.getName());

    public static class NamespaceSeparation {
        public List<Object> groups;
        public List<Object> nodes;
        public List<Object> body;
        public NamespaceSeparation(List<Object> groups, List<Object> nodes, List<Object> body) {
            this.groups = groups;
            this.nodes = nodes;
            this.body = body;
        }
    }

    public static Call get_call_from_func_element(Object func) {
        assert (func instanceof ast.Attribute || func instanceof ast.Name || func instanceof ast.Subscript || func instanceof ast.Call);
        if (func instanceof ast.Attribute) {
            List<String> owner_token = new ArrayList<>();
            Object val = ((ast.Attribute) func).value;
            while (true) {
                try {
                    if (val instanceof ast.Attribute) {
                        owner_token.add(((ast.Attribute) val).attr);
                    } else if (val instanceof ast.Name) {
                        owner_token.add(((ast.Name) val).id);
                    }
                } catch (Exception e) {
                    // pass
                }
                if (val instanceof ast.Attribute) {
                    val = ((ast.Attribute) val).value;
                } else {
                    val = null;
                }
                if (val == null) {
                    break;
                }
            }
            String ownerTokenStr;
            if (!owner_token.isEmpty()) {
                Collections.reverse(owner_token);
                ownerTokenStr = Model.djoin(owner_token.toArray(new String[0]));
            } else {
                ownerTokenStr = Model.OWNER_CONST.get("UNKNOWN_VAR");
            }
            ast.Attribute attrFunc = (ast.Attribute) func;
            return new Call(attrFunc.attr, attrFunc.lineno, ownerTokenStr, false);
        }
        if (func instanceof ast.Name) {
            ast.Name nameFunc = (ast.Name) func;
            return new Call(nameFunc.id, nameFunc.lineno, null, false);
        }
        if (func instanceof ast.Subscript || func instanceof ast.Call) {
            return null;
        }
        return null;
    }

    public static List<Call> make_calls(List<Object> lines) {
        List<Call> calls = new ArrayList<>();
        for (Object tree : lines) {
            for (Object element : ast.walk(tree)) {
                if (!(element instanceof ast.Call)) {
                    continue;
                }
                Object funcField = ((ast.Call) element).func;
                Call call = get_call_from_func_element(funcField);
                if (call != null) {
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    public static List<Variable> process_assign(ast.Assign element) {
        if (!(element.value instanceof ast.Call)) {
            return new ArrayList<>();
        }
        Call call = get_call_from_func_element(((ast.Call) element.value).func);
        if (call == null) {
            return new ArrayList<>();
        }
        List<Variable> ret = new ArrayList<>();
        for (Object target : element.targets) {
            if (!(target instanceof ast.Name)) {
                continue;
            }
            String token = ((ast.Name) target).id;
            ret.add(new Variable(token, call, element.lineno));
        }
        return ret;
    }

    public static List<Variable> process_import(Object element) {
        List<Variable> ret = new ArrayList<>();
        List<ast.alias> names = null;
        int lineno = 0;
        String module = null;
        if (element instanceof ast.Import) {
            names = ((ast.Import) element).names;
            lineno = ((ast.Import) element).lineno;
        } else if (element instanceof ast.ImportFrom) {
            names = ((ast.ImportFrom) element).names;
            lineno = ((ast.ImportFrom) element).lineno;
            module = ((ast.ImportFrom) element).module;
        }
        if (names != null) {
            for (ast.alias single_import : names) {
                assert single_import != null;
                String token = (single_import.asname != null ? single_import.asname : single_import.name);
                String rhs = single_import.name;
                if (module != null && !module.isEmpty()) {
                    rhs = Model.djoin(module, rhs);
                }
                ret.add(new Variable(token, rhs, lineno));
            }
        }
        return ret;
    }

    public static List<Variable> make_local_variables(List<Object> lines, Group parent) {
        List<Variable> variables = new ArrayList<>();
        for (Object tree : lines) {
            for (Object element : ast.walk(tree)) {
                if (element instanceof ast.Assign) {
                    variables.addAll(process_assign((ast.Assign) element));
                }
                if (element instanceof ast.Import || element instanceof ast.ImportFrom) {
                    variables.addAll(process_import(element));
                }
            }
        }
        if (parent.group_type == Model.GROUP_TYPE.get("CLASS") && !lines.isEmpty()) {
            Object firstLine = lines.get(0);
            int lineno = 0;
            if (firstLine instanceof ast.Attribute) {
                lineno = ((ast.Attribute) firstLine).lineno;
            } else if (firstLine instanceof ast.Name) {
                lineno = ((ast.Name) firstLine).lineno;
            } else if (firstLine instanceof ast.FunctionDef) {
                lineno = ((ast.FunctionDef) firstLine).lineno;
            } else if (firstLine instanceof ast.ClassDef) {
                lineno = ((ast.ClassDef) firstLine).lineno;
            }
            variables.add(new Variable("self", parent, lineno));
        }
        List<Variable> filtered = new ArrayList<>();
        for (Variable var : variables) {
            if (var != null) {
                filtered.add(var);
            }
        }
        return filtered;
    }

    public static List<String> get_inherits(ast.ClassDef tree) {
        List<String> inherits = new ArrayList<>();
        for (Object base : tree.bases) {
            if (base instanceof ast.Name) {
                inherits.add(((ast.Name) base).id);
            }
        }
        return inherits;
    }

    class ast {

        public static class Module implements HasBody {
            public List<Object> body;
            public Module() {
                this.body = new ArrayList<>();
            }
            public List<Object> getBody() {
                return this.body;
            }
        }
    
        public static class Attribute {
            public Object value;
            public String attr;
            public int lineno;
            public Attribute(Object value, String attr, int lineno) {
                this.value = value;
                this.attr = attr;
                this.lineno = lineno;
            }
        }
    
        public static class Name {
            public String id;
            public int lineno;
            public Name(String id, int lineno) {
                this.id = id;
                this.lineno = lineno;
            }
        }
    
        public static class Subscript {
            public Object value;
            public int lineno;
            public Subscript(Object value, int lineno) {
                this.value = value;
                this.lineno = lineno;
            }
        }
    
        public static class Call {
            public Object func;
            public int lineno;
            public Call(Object func, int lineno) {
                this.func = func;
                this.lineno = lineno;
            }
        }
    
        public static class FunctionDef implements HasBody {
            public String name;
            public int lineno;
            public List<Object> body;
            public FunctionDef(String name, int lineno) {
                this.name = name;
                this.lineno = lineno;
                this.body = new ArrayList<>();
            }
            public List<Object> getBody() {
                return this.body;
            }
        }
    
        public static class AsyncFunctionDef implements HasBody {
            public String name;
            public int lineno;
            public List<Object> body;
            public AsyncFunctionDef(String name, int lineno) {
                this.name = name;
                this.lineno = lineno;
                this.body = new ArrayList<>();
            }
            public List<Object> getBody() {
                return this.body;
            }
        }
    
        public static class ClassDef implements HasBody {
            public String name;
            public int lineno;
            public List<Object> body;
            public List<Object> bases;
            public ClassDef(String name, int lineno) {
                this.name = name;
                this.lineno = lineno;
                this.body = new ArrayList<>();
                this.bases = new ArrayList<>();
            }
            public List<Object> getBody() {
                return this.body;
            }
        }
    
        public static class Assign {
            public Object value;
            public List<Object> targets;
            public int lineno;
            public Assign(Object value, List<Object> targets, int lineno) {
                this.value = value;
                this.targets = targets;
                this.lineno = lineno;
            }
        }
    
        public static class Import {
            public List<alias> names;
            public int lineno;
            public Import(List<alias> names, int lineno) {
                this.names = names;
                this.lineno = lineno;
            }
        }
    
        public static class ImportFrom {
            public List<alias> names;
            public String module;
            public int lineno;
            public ImportFrom(List<alias> names, String module, int lineno) {
                this.names = names;
                this.module = module;
                this.lineno = lineno;
            }
        }
    
        public static class alias {
            public String name;
            public String asname;
            public alias(String name, String asname) {
                this.name = name;
                this.asname = asname;
            }
        }

        public static Module parse(String raw) {
            return new Module();
        }

        public static List<Object> walk(Object node) {
            List<Object> nodes = new ArrayList<>();
            if (node == null) {
                return nodes;
            }
            nodes.add(node);
            if (node instanceof HasBody) {
                List<Object> body = ((HasBody) node).getBody();
                if (body != null) {
                    for (Object child : body) {
                        nodes.addAll(walk(child));
                    }
                }
            }
            if (node instanceof Attribute) {
                Object child = ((Attribute) node).value;
                nodes.addAll(walk(child));
            }
            if (node instanceof Call) {
                Object child = ((Call) node).func;
                nodes.addAll(walk(child));
            }
            if (node instanceof Assign) {
                Assign assign = (Assign) node;
                nodes.addAll(walk(assign.value));
                if (assign.targets != null) {
                    for (Object target : assign.targets) {
                        nodes.addAll(walk(target));
                    }
                }
            }
            if (node instanceof Import) {
            }
            if (node instanceof ImportFrom) {
            }
            if (node instanceof Subscript) {
                nodes.addAll(walk(((Subscript) node).value));
            }
            if (node instanceof ClassDef) {
                ClassDef classDef = (ClassDef) node;
                if (classDef.bases != null) {
                    for (Object base : classDef.bases) {
                        nodes.addAll(walk(base));
                    }
                }
            }
            return nodes;
        }
    }
    
    interface HasBody {
        List<Object> getBody();
    }

    public static void assert_dependencies() {

    }

    public static ast.Module get_tree(String filename) {
        String raw = "";
        try {
            BufferedReader br = new BufferedReader(new FileReader(filename));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            raw = sb.toString();
        } catch (IOException e) {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(filename), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                raw = sb.toString();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        return ast.parse(raw);
    }

    public static NamespaceSeparation separate_namespaces(HasBody tree) {
        List<Object> groups = new ArrayList<>();
        List<Object> nodes = new ArrayList<>();
        List<Object> body = new ArrayList<>();
        for (Object el : tree.getBody()) {
            if (el instanceof ast.FunctionDef || el instanceof ast.AsyncFunctionDef) {
                nodes.add(el);
            } else if (el instanceof ast.ClassDef) {
                groups.add(el);
            } else if (el instanceof HasBody) {
                NamespaceSeparation tup = separate_namespaces((HasBody) el);
                groups.addAll(tup.groups);
                nodes.addAll(tup.nodes);
                body.addAll(tup.body);
            } else {
                body.add(el);
            }
        }
        return new NamespaceSeparation(groups, nodes, body);
    }

    public static List<Node> make_nodes(ast.FunctionDef tree, Group parent) {
        String token = tree.name;
        int line_number = tree.lineno;
        List<Call> calls = make_calls(tree.getBody());
        List<Variable> variables = make_local_variables(tree.getBody(), parent);
        boolean is_constructor = false;
        if (parent.group_type == Model.GROUP_TYPE.get("CLASS") && (token.equals("__init__") || token.equals("__new__"))) {
            is_constructor = true;
        }
        List<String> import_tokens = new ArrayList<>();
        if (parent.group_type == Model.GROUP_TYPE.get("FILE")) {
            import_tokens.add(Model.djoin(parent.token, token));
        }
        List<Node> ret = new ArrayList<>();
        ret.add(new Node(token, calls, variables, parent, import_tokens, line_number, is_constructor));
        return ret;
    }

    public static Node make_root_node(List<Object> lines, Group parent) {
        String token = "(global)";
        int line_number = 0;
        List<Call> calls = make_calls(lines);
        List<Variable> variables = make_local_variables(lines, parent);
        return new Node(token, calls, variables, parent, null, line_number, false);
    }

    public static Group make_class_group(ast.ClassDef tree, Group parent) {
        assert tree != null;
        NamespaceSeparation ns = separate_namespaces(tree);
        String group_type = Model.GROUP_TYPE.get("CLASS");
        String token = tree.name;
        String display_name = "Class";
        int line_number = tree.lineno;
        List<String> import_tokens = new ArrayList<>();
        import_tokens.add(Model.djoin(parent.token, token));
        List<String> inherits = get_inherits(tree);
        Group class_group = new Group(token, group_type, display_name, import_tokens, line_number, parent, inherits);
        for (Object node_tree : ns.nodes) {
            List<Node> created = make_nodes((ast.FunctionDef) node_tree, class_group);
            if (!created.isEmpty()) {
                class_group.add_node(created.get(0), false);
            }
        }
        for (Object subgroup_tree : ns.groups) {
            logger.warning(String.format("Code2flow does not support nested classes. Skipping %s in %s.", ((ast.ClassDef) subgroup_tree).name, parent.token));
        }
        return class_group;
    }

    public static List<String> file_import_tokens(String filename) {
        File f = new File(filename);
        String name = f.getName();
        if (name.endsWith(".py")) {
            name = name.substring(0, name.length() - 3);
        }
        List<String> tokens = new ArrayList<>();
        tokens.add(name);
        return tokens;
    }
}
