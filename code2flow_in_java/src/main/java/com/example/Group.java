package com.example;

import java.util.*;
import java.util.stream.Collectors;

public class Group implements Model.TokenHolder {
    public String token;
    public String group_type;
    public String display_type;
    public List<String> import_tokens;
    public Integer line_number;
    public Group parent;
    public Object inherits;
    public List<Node> nodes;
    public Node root_node;
    public List<Group> subgroups;
    public String uid;

    public Group(String token, String group_type, String display_type, List<String> import_tokens, Integer line_number, Group parent, Object inherits) {
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
        if (!Model.GROUP_TYPE.containsValue(group_type)) {
            throw new AssertionError("group_type must be one of GROUP_TYPE values");
        }
        this.uid = "cluster_" + Model.bytesToHex(Model.getRandomBytes(4));
    }

    public Group(Group g) {
    }
    public static final Comparator<Group> COMPARATOR =
            Comparator.comparing(Group::getToken);

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
        if (this.group_type.equals(Model.GROUP_TYPE.get("FILE"))) {
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
        if (!this.group_type.equals(Model.GROUP_TYPE.get("CLASS")))
            throw new AssertionError("group_type must be CLASS");
        List<Node> constructors = this.nodes.stream().filter(n -> n.is_constructor).collect(Collectors.toList());
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
            variables.addAll(Model._wrap_as_variables(this.subgroups));

            List<Node> nonRootNodes = this.nodes.stream().filter(n -> n != this.root_node).collect(Collectors.toList());
            variables.addAll(Model._wrap_as_variables(nonRootNodes));
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
            this.parent.subgroups = this.parent.subgroups.stream().filter(g -> g != this).collect(Collectors.toList());
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

            String indented = Arrays.stream(subDot.split("\n")).map(line -> "    " + line).collect(Collectors.joining("\n"));
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