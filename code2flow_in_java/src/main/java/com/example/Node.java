package com.example;

import java.util.*;
import java.util.stream.Collectors;

public class Node implements Model.TokenHolder, Comparable<Node> {
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

    public int compareTo(Node other) {
        return this.token_with_ownership().compareTo(other.token_with_ownership());
    }
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

        this.uid = "node_" + Model.bytesToHex(Model.getRandomBytes(4));
        this.is_leaf = true;
        this.is_trunk = true;
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
                (((Group) this.parent).group_type.equals(Model.GROUP_TYPE.get("CLASS")) ||
                        ((Group) this.parent).group_type.equals(Model.GROUP_TYPE.get("NAMESPACE"))));
    }

    public String token_with_ownership() {
        if (this.is_attr()) {
            return Model.djoin(((Group)this.parent).token, this.token);
        }
        return this.token;
    }

    public String namespace_ownership() {
        Object parentObj = this.parent;
        List<String> ret = new ArrayList<>();
        while (parentObj != null && (parentObj instanceof Group) &&
                ((Group) parentObj).group_type.equals(Model.GROUP_TYPE.get("CLASS"))) {
            ret.add(0, ((Group) parentObj).token);
            parentObj = ((Group) parentObj).parent;
        }
        return Model.djoin(ret);
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
            ret = this.variables.stream().filter(v -> v.line_number != null && v.line_number <= line_number).collect(Collectors.toList());
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
                variable.points_to = Model._resolve_str_variable(variable, file_groups);
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
        attributes.put("fillcolor", Model.NODE_COLOR);
        if (this.is_trunk) {
            attributes.put("fillcolor", Model.TRUNK_COLOR);
        } else if (this.is_leaf) {
            attributes.put("fillcolor", Model.LEAF_COLOR);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.uid).append(" [");
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            sb.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\" ");
        }
        sb.append("]");
        return sb.toString();
    }

    public Map<String, String> to_dict() {
        Map<String, String> ret = new HashMap<>();
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
