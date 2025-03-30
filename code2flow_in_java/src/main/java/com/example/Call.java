package com.example;

import java.util.List;

public class Call {
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
                    for (List<Node> inherit_nodes : (List<List<Node>>)grp.inherits) {
                        for (Node node : inherit_nodes) {
                            if (this.token.equals(node.token)) {
                                return node;
                            }
                        }
                    }
                    if (variable.points_to instanceof String) {
                        if (Model.OWNER_CONST.containsValue((String) variable.points_to)) {
                            return variable.points_to;
                        }
                    }
                }
            }
            if (variable.points_to instanceof Group) {
                Group points_to = (Group) variable.points_to;
                if (points_to.group_type == Model.GROUP_TYPE.get("NAMESPACE")) {
                    String[] parts = this.owner_token.split(".");
                    if (parts.length != 2) {
                        return null;
                    }
                    if (!parts[0].equals(variable.token)) {
                        return null;
                    }
                    for (Node node : points_to.all_nodes()) {
                        if (parts[1].equals(node.namespace_ownership()) && this.token.equals(node.token)) {
                            return node;
                        }
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
                if (grp.group_type.equals(Model.GROUP_TYPE.get("CLASS")) && grp.get_constructor() != null) {
                    return grp.get_constructor();
                }
            }
        }
        return null;
    }
}
