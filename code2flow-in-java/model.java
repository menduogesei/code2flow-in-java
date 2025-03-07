import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

public class model {

    public static final String TRUNK_COLOR = "#966F33";
    public static final String LEAF_COLOR = "#6db33f";
    public static final String[] EDGE_COLORS = {
            "#000000", "#E69F00", "#56B4E9", "#009E73",
            "#F0E442", "#0072B2", "#D55E00", "#CC79A7"
    };
    public static final String NODE_COLOR = "#cccccc";

    public static class Namespace extends HashMap<String, String> {
        public Namespace(String... tokens) {
            for (String token : tokens) {
                this.put(token, token);
            }
        }
        public String __getattr__(String item) {
            return this.get(item);
        }
    }

    public static final Namespace OWNER_CONST = Namespace("UNKNOWN_VAR", "UNKNOWN_MODULE");
    public static final Namespace GROUP_TYPE = Namespace("FILE", "CLASS", "NAMESPACE");

    public static boolean is_installed(String executable_cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            path = path.replace("\"", "").trim();
            File exeFile = new File(path, executable_cmd);
            if (exeFile.isFile() && exeFile.canExecute()) {
                return true;
            }
        }
        return false;
    }
    
    public static String djoin(Object... tup) {
        if (tup.length == 1 && (tup[0] instanceof List)) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) tup[0];
            return list.stream().map(Object::toString).collect(Collectors.joining("."));
        }
        return Arrays.stream(tup).map(Object::toString).collect(Collectors.joining("."));
    }
    
    public static <T> List<T> flatten(List<List<T>> list_of_lists) {
        List<T> ret = new ArrayList<>();
        for (List<T> sublist : list_of_lists) {
            ret.addAll(sublist);
        }
        return ret;
    }

    public static Object _resolve_str_variable(Variable variable, List<Group> file_groups) {
        for (Group file_group : file_groups) {
            for (Node node : file_group.all_nodes()) {
                for (String ot : node.import_tokens) {
                    if (ot.equals(variable.points_to)) {
                        return node;
                    }
                }
            }
            for (Group group : file_group.all_groups()) {
                for (String ot : group.import_tokens) {
                    if (ot.equals(variable.points_to)) {
                        return group;
                    }
                }
            }
        }
        return OWNER_CONST.UNKNOWN_MODULE;
    }
    
    public static List<Variable> _wrap_as_variables(List<Object> sequence) {
        List<Variable> ret = new ArrayList<>();
        for (Object el : sequence) {
            if (el instanceof TokenHolder) {
                TokenHolder th = (TokenHolder) el;
                ret.add(new Variable(th.getToken(), el, th.getLineNumber()));
            }
        }
        return ret;
    }

    public interface TokenHolder {
        String getToken();
        Integer getLineNumber();
    }

    public static abstract class BaseLanguage {

        public abstract void assert_dependencies();

        public abstract Object get_tree(String filename, Object lang_params);

        public abstract Object separate_namespaces(Object tree);

        public abstract List<Node> make_nodes(Object tree, Group parent);

        public abstract Node make_root_node(List<Object> lines, Group parent);

        public abstract Group make_class_group(Object tree, Group parent);
    }

    public static class Variable {
        public String token;
        public Object points_to;
        public Integer line_number;

        public Variable(String token, Object points_to, Integer line_number) {
            if (token == null) throw new IllegalArgumentException("token cannot be null");
            if (points_to == null) throw new IllegalArgumentException("points_to cannot be null");
            this.token = token;
            this.points_to = points_to;
            this.line_number = line_number;
        }

        @Override
        public String toString() {
            return "<Variable token=" + this.token + " points_to=" + String.valueOf(this.points_to) + ">";
        }

        public String to_string() {
            if (this.points_to != null && (this.points_to instanceof Group || this.points_to instanceof Node)) {
                String ptsToken;
                ptsToken = (TokenHolder)this.points_to.getToken();
                return this.token + "->" + ptsToken;
            }
            return this.token + "->" + this.points_to;
        }
    }

    public static class Call {
        public String token;
        public Integer line_number;
        public String owner_token;
        public boolean definite_constructor;

        public Call(String token, Integer line_number, String owner_token, boolean definite_constructor) {
            this.token = token;
            this.owner_token = owner_token;
            this.line_number = line_number;
            this.definite_constructor = definite_constructor;
        }

        @Override
        public String toString() {
            return "<Call owner_token=" + this.owner_token + " token=" + this.token + ">";
        }

        public String to_string() {
            if (this.owner_token != null) {
                return this.owner_token + "." + this.token + "()";
            }
            return this.token + "()";
        }

        public boolean is_attr() {
            return this.owner_token != null;
        }

        public Object matches_variable(Variable variable) {
            if (this.is_attr()) {
                if (this.owner_token.equals(variable.token)) {
                    if (variable.points_to instanceof Group) {
                        Group grp = (Group) variable.points_to;
                        for (Node node : grp.nodes) {
                            if (this.token.equals(node.token)) {
                                return node;
                            }
                        }
                        for (List<Node> inherit_nodes : grp.inherits) {
                            for (Node node : inherit_nodes) {
                                if (this.token.equals(node.token)) {
                                    return node;
                                }
                            }
                        }
                        if (variable.points_to instanceof String) {
                            if (OWNER_CONST.contains((String) variable.points_to)) {
                                return variable.points_to;
                            }
                        }
                    }
                }
                if (variable.points_to instanceof Group && variable.points_to.group_type == GROUP_TYPE.NAMESPACE) {
                    String[] parts = this.owner_token.split(".");
                    if (parts.length != 2) {
                        return null;
                    }
                    if (!parts[0].equals(variable.token)) {
                        return null;
                    }
                    for (Node node : variable.points_to.all_nodes()) {
                        if (parts[1].equals(node.namespace_ownership()) && this.token.equals(node.token)) {
                            return node;
                        }
                    }
                }
                return null;
            }
            if (this.token.equals(variable.token)) {
                if (variable.points_to instanceof Node) {
                    return (Node) variable.points_to;
                }
                if (variable.points_to instanceof Group) {
                    Group grp = (Group) variable.points_to;
                    if (grp.group_type.equals(GROUP_TYPE.CLASS) && grp.get_constructor() != null) {
                        return grp.get_constructor();
                    }
                }
            }
            return null;
        }
    }

    public static class Node implements TokenHolder {
        public String token;
        public Integer line_number;
        public List<Call> calls;
        public List<Variable> variables;
        public List<String> import_tokens;
        public Object parent;
        public boolean is_constructor;

        public String uid;
        public boolean is_leaf;
        public boolean is_trunk;

        public Node(String token, List<Call> calls, List<Variable> variables, Object parent, List<String> import_tokens, Integer line_number, boolean is_constructor) {
            this.token = token;
            this.line_number = line_number;
            this.calls = calls;
            this.variables = variables;
            if (import_tokens != null) {
                this.import_tokens = import_tokens;
            } else {
                this.import_tokens = new ArrayList<>();
            }
            this.parent = parent;
            this.is_constructor = is_constructor;

            this.uid = "node_" + this.bytesToHex(this.getRandomBytes(4));
            this.is_leaf = true;
            this.is_trunk = true;
        }

        private byte[] getRandomBytes(int numBytes) {
            SecureRandom random = new SecureRandom();
            byte[] b = new byte[numBytes];
            random.nextBytes(b);
            return b;
        }

        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "<Node token=" + this.token + " parent=" + String.valueOf(this.parent) + ">";
        }

        public boolean lessThan(Node other) {
            return this.name().compareTo(other.name()) < 0;
        }

        public String name() {
            return this.first_group().filename() + "::" + this.token_with_ownership();
        }

        public Group first_group() {
            Object parentObj = this.parent;
            while (!(parentObj instanceof Group)) {
                if (parentObj instanceof Node) {
                    parentObj = ((Node) parentObj).parent;
                } else {
                    break;
                }
            }
            return (Group) parentObj;
        }

        public Group file_group() {
            Object parentObj = this.parent;
            while (parentObj instanceof Node || (parentObj instanceof Group && ((Group) parentObj).parent != null)) {
                if (parentObj instanceof Node) {
                    parentObj = ((Node) parentObj).parent;
                } else if (parentObj instanceof Group) {
                    parentObj = ((Group) parentObj).parent;
                }
            }
            return (Group) parentObj;
        }

        public boolean is_attr() {
            return (this.parent != null &&
                    (this.parent instanceof Group) &&
                    (((Group) this.parent).group_type.equals(GROUP_TYPE.CLASS) ||
                     ((Group) this.parent).group_type.equals(GROUP_TYPE.NAMESPACE)));
        }

        public String token_with_ownership() {
            if (this.is_attr()) {
                return djoin(((Group)this.parent).token, this.token);
            }
            return this.token;
        }

        public String namespace_ownership() {
            Object parentObj = this.parent;
            List<String> ret = new ArrayList<>();
            while (parentObj != null && (parentObj instanceof Group) &&
                    ((Group) parentObj).group_type.equals(GROUP_TYPE.CLASS)) {
                ret.add(0, ((Group) parentObj).token);
                parentObj = ((Group) parentObj).parent;
            }
            return djoin(ret);
        }

        public String label() {
            if (this.line_number != null) {
                return this.line_number + ": " + this.token + "()";
            }
            return this.token + "()";
        }

        public void remove_from_parent() {
            Group fg = this.first_group();
            fg.nodes = fg.nodes.stream().filter(n -> n != this).collect(Collectors.toList());
        }

        public List<Variable> get_variables(Integer line_number) {
            List<Variable> ret;
            if (line_number == null) {
                ret = new ArrayList<>(this.variables);
            } else {
                ret = this.variables.stream()
                        .filter(v -> v.line_number != null && v.line_number <= line_number)
                        .collect(Collectors.toList());
            }
            boolean anyLine = ret.stream().anyMatch(v -> v.line_number != null);
            if (anyLine) {
                ret.sort((v1, v2) -> {
                    if (v1.line_number == null || v2.line_number == null) return 0;
                    return v2.line_number.compareTo(v1.line_number);
                });
            }
            Object parentObj = this.parent;
            while (parentObj != null) {
                if (parentObj instanceof Node) {
                    ret.addAll(((Node) parentObj).get_variables(null));
                    parentObj = ((Node) parentObj).parent;
                } else if (parentObj instanceof Group) {
                    ret.addAll(((Group) parentObj).get_variables(null));
                    parentObj = ((Group) parentObj).parent;
                } else {
                    break;
                }
            }
            return ret;
        }

        public void resolve_variables(List<Group> file_groups) {
            for (Variable variable : this.variables) {
                if (variable.points_to instanceof String) {
                    variable.points_to = Util._resolve_str_variable(variable, file_groups);
                } else if (variable.points_to instanceof Call) {
                    Call call = (Call) variable.points_to;
                    if (call.is_attr() && !call.definite_constructor) {
                        continue;
                    }
                    for (Group file_group : file_groups) {
                        for (Group group : file_group.all_groups()) {
                            if (group.token.equals(call.token)) {
                                variable.points_to = group;
                            }
                        }
                    }
                } else {
                    if (!(variable.points_to instanceof Node) && !(variable.points_to instanceof Group)) {
                        throw new AssertionError("points_to is not an instance of Node or Group");
                    }
                }
            }
        }

        public String to_dot() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("label", this.label());
            attributes.put("name", this.name());
            attributes.put("shape", "rect");
            attributes.put("style", "rounded,filled");
            attributes.put("fillcolor", NODE_COLOR);
            if (this.is_trunk) {
                attributes.put("fillcolor", TRUNK_COLOR);
            } else if (this.is_leaf) {
                attributes.put("fillcolor", LEAF_COLOR);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(this.uid).append(" [");
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\" ");
            }
            sb.append("]");
            return sb.toString();
        }

        public Map<String, Object> to_dict() {
            Map<String, Object> ret = new HashMap<>();
            ret.put("uid", this.uid);
            ret.put("label", this.label());
            ret.put("name", this.name());
            return ret;
        }

        @Override
        public String getToken() {
            return token;
        }

        @Override
        public Integer getLineNumber() {
            return line_number;
        }
    }

    public static class Edge {
        public Node node0;
        public Node node1;

        public Edge(Node node0, Node node1) {
            this.node0 = node0;
            this.node1 = node1;

            node0.is_leaf = false;
            node1.is_trunk = false;
        }

        @Override
        public String toString() {
            return "<Edge " + this.node0 + " -> " + this.node1 + ">";
        }

        public boolean lessThan(Edge other) {
            if (this.node0.equals(other.node0)) {
                return this.node1.lessThan(other.node1);
            }
            return this.node0.lessThan(other.node0);
        }

        public String to_dot() {
            String ret = this.node0.uid + " -> " + this.node1.uid;
            String[] parts = this.node0.uid.split("_");
            int hexValue = Integer.parseInt(parts[parts.length - 1], 16);
            int source_color = hexValue % EDGE_COLORS.length;
            ret += " [color=\"" + EDGE_COLORS[source_color] + "\" penwidth=\"2\"]";
            return ret;
        }

        public Map<String, Object> to_dict() {
            Map<String, Object> ret = new HashMap<>();
            ret.put("source", this.node0.uid);
            ret.put("target", this.node1.uid);
            ret.put("directed", true);
            return ret;
        }
    }


    public static class Group implements TokenHolder {
        public String token;
        public String group_type;
        public String display_type;
        public List<String> import_tokens;
        public Integer line_number;
        public Group parent;
        public List<List<Node>> inherits;
        public List<Node> nodes;
        public Node root_node;
        public List<Group> subgroups;
        public String uid;

        public Group(String token, String group_type, String display_type, List<String> import_tokens, Integer line_number, Group parent, List<List<Node>> inherits) {
            this.token = token;
            this.line_number = line_number;
            this.nodes = new ArrayList<>();
            this.root_node = null;
            this.subgroups = new ArrayList<>();
            this.parent = parent;
            this.group_type = group_type;
            this.display_type = display_type;
            if (import_tokens != null) {
                this.import_tokens = import_tokens;
            } else {
                this.import_tokens = new ArrayList<>();
            }
            if (inherits != null) {
                this.inherits = inherits;
            } else {
                this.inherits = new ArrayList<>();
            }
            if (!GROUP_TYPE.contains(group_type)) {
                throw new AssertionError("group_type must be one of GROUP_TYPE values");
            }
            this.uid = "cluster_" + bytesToHex(getRandomBytes(4));
        }

        private byte[] getRandomBytes(int numBytes) {
            SecureRandom random = new SecureRandom();
            byte[] b = new byte[numBytes];
            random.nextBytes(b);
            return b;
        }

        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "<Group token=" + this.token + " type=" + this.display_type + ">";
        }

        public boolean lessThan(Group other) {
            return this.label().compareTo(other.label()) < 0;
        }

        public String label() {
            return this.display_type + ": " + this.token;
        }

        public String filename() {
            if (this.group_type.equals(GROUP_TYPE.FILE)) {
                return this.token;
            }
            return this.parent.filename();
        }

        public void add_subgroup(Group sg) {
            this.subgroups.add(sg);
        }

        public void add_node(Node node, boolean is_root) {
            this.nodes.add(node);
            if (is_root) {
                this.root_node = node;
            }
        }

        public List<Node> all_nodes() {
            List<Node> ret = new ArrayList<>(this.nodes);
            for (Group subgroup : this.subgroups) {
                ret.addAll(subgroup.all_nodes());
            }
            return ret;
        }

        public Node get_constructor() {
            if (!this.group_type.equals(GROUP_TYPE.CLASS_))
                throw new AssertionError("group_type must be CLASS");
            List<Node> constructors = this.nodes.stream()
                    .filter(n -> n.is_constructor)
                    .collect(Collectors.toList());
            if (!constructors.isEmpty()) {
                return constructors.get(0);
            }
            return null;
        }

        public List<Group> all_groups() {
            List<Group> ret = new ArrayList<>();
            ret.add(this);
            for (Group subgroup : this.subgroups) {
                ret.addAll(subgroup.all_groups());
            }
            return ret;
        }

        public List<Variable> get_variables(Integer line_number) {
            if (this.root_node != null) {
                List<Variable> variables = new ArrayList<>();
                variables.addAll(this.root_node.variables);
                variables.addAll(Util._wrap_as_variables(this.subgroups));

                List<Object> nonRootNodes = this.nodes.stream()
                        .filter(n -> n != this.root_node)
                        .collect(Collectors.toList());
                variables.addAll(Util._wrap_as_variables(nonRootNodes));
                boolean anyLine = variables.stream().anyMatch(v -> v.line_number != null);
                if (anyLine) {
                    variables.sort((v1, v2) -> {
                        if (v1.line_number == null || v2.line_number == null) return 0;
                        return v2.line_number.compareTo(v1.line_number);
                    });
                }
                return variables;
            } else {
                return new ArrayList<>();
            }
        }

        public void remove_from_parent() {
            if (this.parent != null) {
                this.parent.subgroups = this.parent.subgroups.stream()
                        .filter(g -> g != this)
                        .collect(Collectors.toList());
            }
        }

        public List<Group> all_parents() {
            if (this.parent != null) {
                List<Group> ret = new ArrayList<>();
                ret.add(this.parent);
                ret.addAll(this.parent.all_parents());
                return ret;
            }
            return new ArrayList<>();
        }

        public String to_dot() {
            StringBuilder sb = new StringBuilder();
            sb.append("subgraph ").append(this.uid).append(" {\n");
            if (!this.nodes.isEmpty()) {
                sb.append("    ");
                String joined = this.nodes.stream().map(n -> n.uid).collect(Collectors.joining(" "));
                sb.append(joined).append(";\n");
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("label", this.label());
            attributes.put("name", this.token);
            attributes.put("style", "filled");
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                sb.append("    ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\";\n");
            }
            sb.append("    graph[style=dotted];\n");
            for (Group subgroup : this.subgroups) {
                String subDot = subgroup.to_dot();

                String indented = Arrays.stream(subDot.split("\n"))
                        .map(line -> "    " + line)
                        .collect(Collectors.joining("\n"));
                sb.append(indented).append("\n");
            }
            sb.append("};\n");
            return sb.toString();
        }

        @Override
        public String getToken() {
            return this.token;
        }

        @Override
        public Integer getLineNumber() {
            return this.line_number;
        }
    }
    
    public static void main(String[] args) throws IOException {
        System.out.println("is_installed('java'): " + Util.is_installed("java"));
        
        System.out.println("djoin('a','b','c'): " + Util.djoin("a", "b", "c"));
        
        List<List<Integer>> listOfLists = new ArrayList<>();
        listOfLists.add(Arrays.asList(1, 2, 3));
        listOfLists.add(Arrays.asList(4, 5));
        System.out.println("Flattened list: " + Util.flatten(listOfLists));
    }
}