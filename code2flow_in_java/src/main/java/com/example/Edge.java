package com.example;

import java.util.HashMap;
import java.util.Map;

public class Edge implements Comparable<Edge> {
    public Node node0;
    public Node node1;

    public int compareTo(Edge other) {
        int cmp = this.node0.compareTo(other.node0);
        return cmp != 0 ? cmp : this.node1.compareTo(other.node1);
    }

    public Edge(Node node0, Node node1) {
        this.node0 = node0;
        this.node1 = node1;

        this.node0.is_leaf = false;
        this.node1.is_trunk = false;
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
        int source_color = hexValue % Model.EDGE_COLORS.length;
        ret += " [color=\"" + Model.EDGE_COLORS[source_color] + "\" penwidth=\"2\"]";
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
