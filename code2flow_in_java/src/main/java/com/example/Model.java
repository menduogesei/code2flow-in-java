package com.example;

import java.io.File;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

public class Model {

    public static final String TRUNK_COLOR = "#966F33";
    public static final String LEAF_COLOR = "#6db33f";
    public static final String[] EDGE_COLORS = {
            "#000000", "#E69F00", "#56B4E9", "#009E73",
            "#F0E442", "#0072B2", "#D55E00", "#CC79A7"
    };
    public static final String NODE_COLOR = "#cccccc";

    public static final Map<String, String> OWNER_CONST = new HashMap<String, String>();
    public static final Map<String, String> GROUP_TYPE = new HashMap<String, String>();
    static{
        OWNER_CONST.put("UNKNOWN_VAR", "UNKNOWN_VAR");
        OWNER_CONST.put("UNKNOWN_MODULE", "UNKNOWN_MODULE");
        GROUP_TYPE.put("FILE", "FILE");
        GROUP_TYPE.put("CLASS", "CLASS");
        GROUP_TYPE.put("NAMESPACE", "NAMESPACE");
    }

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
        return OWNER_CONST.get("UNKNOWN_MODULE");
    }
    
    public static List<Variable> _wrap_as_variables(List<? extends Object> sequence) {
        List<Variable> ret = new ArrayList<>();
        for (Object el : sequence) {
            if (el instanceof TokenHolder) {
                TokenHolder th = (TokenHolder) el;
                ret.add(new Variable(th.getToken(), el, th.getLineNumber()));
            }
        }
        return ret;
    }

    public static byte[] getRandomBytes(int numBytes) {
        SecureRandom random = new SecureRandom();
        byte[] b = new byte[numBytes];
        random.nextBytes(b);
        return b;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public interface TokenHolder {
        String getToken();
        Integer getLineNumber();
    }
}




