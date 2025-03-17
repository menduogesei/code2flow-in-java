import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import model.OWNER_CONST;
import model.GROUP_TYPE;
import model.Group;
import model.Node;
import model.Call;
import model.Variable;
import model.BaseLanguage;
import model.djoin;

public class Python extends BaseLanguage {

    private static final Logger logging = Logger.getLogger(Python.class.getName());

    public static Call get_call_from_func_element(ast.AST func) {
        if (!(func instanceof ast.Attribute ||
              func instanceof ast.Name ||
              func instanceof ast.Subscript ||
              func instanceof ast.ACall)) {
            throw new AssertionError("func is not a valid type");
        }
        if (func instanceof ast.Attribute) {
            List<String> owner_token_list = new ArrayList<>();
            ast.AST val = ((ast.Attribute) func).value;
            while (true) {
                String token = null;
                if (val instanceof ast.Attribute) {
                    token = ((ast.Attribute) val).attr;
                } else if (val instanceof ast.Name) {
                    token = ((ast.Name) val).id;
                }
                if (token != null) {
                    owner_token_list.add(token);
                }
                if (val instanceof ast.Attribute) {
                    val = ((ast.Attribute) val).value;
                } else {
                    break;
                }
                if (val == null) {
                    break;
                }
            }
            String owner_token;
            if (!owner_token_list.isEmpty()) {
                Collections.reverse(owner_token_list);
                owner_token = djoin.join(owner_token_list.toArray(new String[0]));
            } else {
                owner_token = OWNER_CONST.UNKNOWN_VAR;
            }
            return new Call(((ast.Attribute) func).attr, func.lineno, owner_token);
        }
        if (func instanceof ast.Name) {
            return new Call(((ast.Name) func).id, func.lineno);
        }
        if (func instanceof ast.Subscript || func instanceof ast.ACall) {
            return null;
        }
        return null;
    }

    public static List<Call> make_calls(List<ast.AST> lines) {
        List<Call> calls = new ArrayList<>();
        for (ast.AST tree : lines) {
            for (ast.AST element : ast.ASTUtil.walk(tree)) {
                if (!(element instanceof ast.ACall)) {
                    continue;
                }
                Call call = get_call_from_func_element(((ast.ACall) element).func);
                if (call != null) {
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    public static List<Variable> process_assign(ast.Assign element) {
        List<Variable> ret = new ArrayList<>();
        if (!(element.value instanceof ast.ACall)) {
            return ret;
        }
        Call call = get_call_from_func_element(((ast.ACall) element.value).func);
        if (call == null) {
            return ret;
        }
        for (ast.AST target : element.targets) {
            if (!(target instanceof ast.Name)) {
                continue;
            }
            String token = ((ast.Name) target).id;
            ret.add(new Variable(token, call, element.lineno));
        }
        return ret;
    }

    public static List<Variable> process_import(ast.AST element) {
        List<Variable> ret = new ArrayList<>();
        List<ast.alias> names = null;
        String module = null;
        int lineno = element.lineno;
        if (element instanceof ast.Import) {
            names = ((ast.Import) element).names;
        } else if (element instanceof ast.ImportFrom) {
            names = ((ast.ImportFrom) element).names;
            module = ((ast.ImportFrom) element).module;
        }
        if (names != null) {
            for (ast.alias single_import : names) {
                String token = (single_import.asname != null) ? single_import.asname : single_import.name;
                String rhs = single_import.name;
                if (module != null && !module.isEmpty()) {
                    rhs = djoin.join(module, rhs);
                }
                ret.add(new Variable(token, rhs, lineno));
            }
        }
        return ret;
    }

    public static List<Variable> make_local_variables(List<ast.AST> lines, Group parent) {
        List<Variable> variables = new ArrayList<>();
        for (ast.AST tree : lines) {
            for (ast.AST element : ast.ASTUtil.walk(tree)) {
                if (element instanceof ast.Assign) {
                    variables.addAll(process_assign((ast.Assign) element));
                }
                if (element instanceof ast.Import || element instanceof ast.ImportFrom) {
                    variables.addAll(process_import(element));
                }
            }
        }
        if (parent.group_type == GROUP_TYPE.CLASS) {
            variables.add(new Variable("self", parent, lines.get(0).lineno));
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
        if (tree.bases != null) {
            for (ast.AST base : tree.bases) {
                if (base instanceof ast.Name) {
                    inherits.add(((ast.Name) base).id);
                }
            }
        }
        return inherits;
    }

    public static class PythonLanguage extends Python {

    }

    @Override
    public void assert_dependencies() {
        // pass
    }

    public static ast.AST get_tree(String filename, Object _unused) {
        String raw = "";
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filename));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            raw = sb.toString();
        } catch (IOException e) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                raw = sb.toString();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return ast.ASTUtil.parse(raw);
    }

    public static SeparateNamespacesResult separate_namespaces(ast.AST tree) {
        List<ast.AST> groups = new ArrayList<>();
        List<ast.AST> nodes = new ArrayList<>();
        List<ast.AST> body = new ArrayList<>();
        if (tree instanceof ast.Module && ((ast.Module) tree).body != null) {
            for (ast.AST el : ((ast.Module) tree).body) {
                if (el instanceof ast.FunctionDef || el instanceof ast.AsyncFunctionDef) {
                    nodes.add(el);
                } else if (el instanceof ast.ClassDef) {
                    groups.add(el);
                } else if (elHasBody(el)) {
                    SeparateNamespacesResult tup = separate_namespaces(el);
                    groups.addAll(tup.groups);
                    nodes.addAll(tup.nodes);
                    body.addAll(tup.body);
                } else {
                    body.add(el);
                }
            }
        } else if (tree.hasBody()) {
            List<ast.AST> treeBody = tree.getBody();
            for (ast.AST el : treeBody) {
                if (el instanceof ast.FunctionDef || el instanceof ast.AsyncFunctionDef) {
                    nodes.add(el);
                } else if (el instanceof ast.ClassDef) {
                    groups.add(el);
                } else if (elHasBody(el)) {
                    SeparateNamespacesResult tup = separate_namespaces(el);
                    groups.addAll(tup.groups);
                    nodes.addAll(tup.nodes);
                    body.addAll(tup.body);
                } else {
                    body.add(el);
                }
            }
        }
        return new SeparateNamespacesResult(groups, nodes, body);
    }

    private static boolean elHasBody(ast.AST el) {
        return el.hasBody();
    }

    public static List<Node> make_nodes(ast.FunctionDef tree, Group parent) {
        String token = tree.name;
        int line_number = tree.lineno;
        List<Call> calls = make_calls(tree.body);
        List<Variable> variables = make_local_variables(tree.body, parent);
        boolean is_constructor = false;
        if (parent.group_type == GROUP_TYPE.CLASS && (token.equals("__init__") || token.equals("__new__"))) {
            is_constructor = true;
        }
        List<String> import_tokens = new ArrayList<>();
        if (parent.group_type == GROUP_TYPE.FILE) {
            import_tokens.add(djoin.join(parent.token, token));
        }
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(token, calls, variables, parent, import_tokens, line_number, is_constructor));
        return nodes;
    }

    public static Node make_root_node(List<ast.AST> lines, Group parent) {
        String token = "(global)";
        int line_number = 0;
        List<Call> calls = make_calls(lines);
        List<Variable> variables = make_local_variables(lines, parent);
        return new Node(token, calls, variables, line_number, parent);
    }

    public static Group make_class_group(ast.ClassDef tree, Group parent) {
        if (!(tree instanceof ast.ClassDef)) {
            throw new AssertionError("tree is not a ClassDef");
        }
        SeparateNamespacesResult sep = separate_namespaces(tree);
        List<ast.AST> subgroup_trees = sep.groups;
        List<ast.AST> node_trees = sep.nodes;

        GROUP_TYPE group_type = GROUP_TYPE.CLASS;
        String token = tree.name;
        String display_name = "Class";
        int line_number = tree.lineno;

        List<String> import_tokens = new ArrayList<>();
        import_tokens.add(djoin.join(parent.token, token));
        List<String> inherits = get_inherits(tree);

        Group class_group = new Group(token, group_type, display_name, import_tokens, inherits, line_number, parent);

        for (ast.AST node_tree : node_trees) {
            List<Node> nodes = make_nodes((ast.FunctionDef) node_tree, class_group);
            if (!nodes.isEmpty()) {
                class_group.add_node(nodes.get(0));
            }
        }

        for (ast.AST subgroup_tree : subgroup_trees) {
            logging.warning(String.format("Code2flow does not support nested classes. Skipping %r in %r.",
                    ((ast.ClassDef) subgroup_tree).name, parent.token));
        }
        return class_group;
    }

    public static List<String> file_import_tokens(String filename) {
        File file = new File(filename);
        String name = file.getName();
        if (name.endsWith(".py")) {
            name = name.substring(0, name.length() - 3);
        }
        List<String> tokens = new ArrayList<>();
        tokens.add(name);
        return tokens;
    }
}

public abstract class AST {
    public int lineno;

    public boolean hasBody() {
        return false;
    }

    public List<AST> getBody() {
        return new ArrayList<>();
    }
}

class Module extends AST {
    public List<AST> body;

    public Module(List<AST> body) {
        this.body = body;
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public List<AST> getBody() {
        return body;
    }
}

class Attribute extends AST {
    public AST value;
    public String attr;

    public Attribute(AST value, String attr, int lineno) {
        this.value = value;
        this.attr = attr;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return value != null;
    }

    @Override
    public List<AST> getBody() {
        List<AST> lst = new ArrayList<>();
        if (value != null) {
            lst.add(value);
        }
        return lst;
    }
}

class Name extends AST {
    public String id;

    public Name(String id, int lineno) {
        this.id = id;
        this.lineno = lineno;
    }
}

class Subscript extends AST {
    public AST value;

    public Subscript(AST value, int lineno) {
        this.value = value;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return value != null;
    }

    @Override
    public List<AST> getBody() {
        List<AST> lst = new ArrayList<>();
        if (value != null) {
            lst.add(value);
        }
        return lst;
    }
}

class ACall extends AST {
    public AST func;

    public ACall(AST func, int lineno) {
        this.func = func;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        if (func != null) {
            return true;
        }
        return false;
    }

    @Override
    public List<AST> getBody() {
        List<AST> lst = new ArrayList<>();
        if (func != null) {
            lst.add(func);
        }
        return lst;
    }
}

class Assign extends AST {
    public AST value;
    public List<AST> targets;

    public Assign(AST value, List<AST> targets, int lineno) {
        this.value = value;
        this.targets = targets;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return targets != null && !targets.isEmpty();
    }

    @Override
    public List<AST> getBody() {
        return targets;
    }
}

class alias extends AST {
    public String name;
    public String asname;

    public alias(String name, String asname, int lineno) {
        this.name = name;
        this.asname = asname;
        this.lineno = lineno;
    }
}

class Import extends AST {
    public List<alias> names;

    public Import(List<alias> names, int lineno) {
        this.names = names;
        this.lineno = lineno;
    }
}

class ImportFrom extends AST {
    public String module;
    public List<alias> names;

    public ImportFrom(String module, List<alias> names, int lineno) {
        this.module = module;
        this.names = names;
        this.lineno = lineno;
    }
}

class FunctionDef extends AST {
    public String name;
    public List<AST> body;

    public FunctionDef(String name, List<AST> body, int lineno) {
        this.name = name;
        this.body = body;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public List<AST> getBody() {
        return body;
    }
}

class AsyncFunctionDef extends AST {
    public String name;
    public List<AST> body;

    public AsyncFunctionDef(String name, List<AST> body, int lineno) {
        this.name = name;
        this.body = body;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public List<AST> getBody() {
        return body;
    }
}

class ClassDef extends AST {
    public String name;
    public List<AST> body;
    public List<AST> bases;

    public ClassDef(String name, List<AST> bases, List<AST> body, int lineno) {
        this.name = name;
        this.bases = bases;
        this.body = body;
        this.lineno = lineno;
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public List<AST> getBody() {
        return body;
    }
}

class ASTUtil {
    public static List<AST> walk(AST tree) {
        List<AST> nodes = new ArrayList<>();
        walkHelper(tree, nodes);
        return nodes;
    }

    private static void walkHelper(AST node, List<AST> nodes) {
        if (node == null) {
            return;
        }
        nodes.add(node);
        if (node.hasBody()) {
            for (AST child : node.getBody()) {
                walkHelper(child, nodes);
            }
        }
    }

    public static AST parse(String raw) {
        return new Module(new ArrayList<AST>());
    }
}

class SeparateNamespacesResult {
    public List<ast.AST> groups;
    public List<ast.AST> nodes;
    public List<ast.AST> body;

    public SeparateNamespacesResult(List<ast.AST> groups, List<ast.AST> nodes, List<ast.AST> body) {
        this.groups = groups;
        this.nodes = nodes;
        this.body = body;
    }
    
}