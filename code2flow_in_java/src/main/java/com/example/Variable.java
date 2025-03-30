package com.example;

public class Variable {
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
            Model.TokenHolder points_to = (Model.TokenHolder)this.points_to;
            ptsToken = points_to.getToken();
            return this.token + "->" + ptsToken;
        }
        return this.token + "->" + this.points_to;
    }
}

